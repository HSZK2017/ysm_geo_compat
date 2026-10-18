package com.ysmef.geomodel.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.geomodel.YSMGeoCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Owns texture registrations, decode tickets, GL uploads, and delayed releases. */
final class YSMTextureRegistry {

    private YSMTextureRegistry() {}

    /**
     * textureRL string -> original encoded texture bytes, until the texture is
     * uploaded (registered from the conversion path, drained on the render
     * thread). Concurrent because the conversion runs off-thread.
     */
    private static final Map<String, byte[]> TEXTURE_DATA = new ConcurrentHashMap<>();

    /** textureRL string -> true once registered in the texture manager */
    private static final Map<String, Boolean> UPLOADED_TEXTURES = new ConcurrentHashMap<>();

    /** textureRL string -> true when the texture has translucent pixels (alpha < 253). */
    private static final Map<String, Boolean> TEXTURE_TRANSLUCENT = new ConcurrentHashMap<>();

    /**
     * textureRL string -> how many models reference that texture. A texture can
     * be shared (a model pack's extra_textures can point at another model's
     * file), so eviction must only release it when the last referencing model
     * goes away - otherwise a still-visible maid renders untextured.
     */
    private static final Map<String, Integer> TEXTURE_REFS = new ConcurrentHashMap<>();

    /** modelId -> texture resource-location strings registered for it (eviction). */
    private static final Map<String, Set<String>> MODEL_TEXTURES = new ConcurrentHashMap<>();

    /**
     * Textures whose decode finished on the background pool and whose GL upload
     * is still pending. The DynamicTexture upload must run on the render thread,
     * so the decoded image waits here for {@link #processPendingTextureUploads}.
     */
    private static final Queue<TextureUploadTask> COMPLETED_UPLOADS = new ConcurrentLinkedQueue<>();

    /** Each registration or restored eviction receives a distinct texture identity. */
    private static final AtomicLong NEXT_TEXTURE_TOKEN = new AtomicLong();
    static final Map<String, Long> TEXTURE_TOKENS = new ConcurrentHashMap<>();

    /** textureRL -> token of the decode currently owned by the background pool. */
    static final Map<String, Long> PENDING_TEXTURE_DECODES = new ConcurrentHashMap<>();

    /** textureRL string -> ticks left before its GL texture is released. */
    static final Map<String, Integer> PENDING_TEXTURE_RELEASES = new ConcurrentHashMap<>();

    /**
     * Wall-clock budget for the per-frame texture upload drain (ModernYSM's
     * UploadManager uses the same idea). Uploads are GL calls and cannot be
     * spread across threads; bounding the time keeps a burst of first-seen
     * models from stalling one frame, at the cost of a few frames of
     * missing-texture rendering.
     */
    private static final long TEXTURE_UPLOAD_BUDGET_NANOS = 10_000_000L;

    /**
     * Background image-decode pool. Decoding is pure CPU work; only the resulting
     * GL upload has to happen on the render thread.
     */
    private static final ExecutorService DECODE_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "ysm-geo-texture");
                thread.setDaemon(true);
                return thread;
            });

    /** One decoded texture waiting for its render-thread GL upload. */
    private record TextureUploadTask(ResourceLocation location, NativeImage image,
                                     boolean translucent, long token) {}

    /**
     * Drop every texture's registered bytes and upload state (resource reload).
     *
     * Without this the reload was a no-op for textures: {@code UPLOADED_TEXTURES}
     * still contained the resource location, so {@code ensureTextureUploaded}
     * returned immediately and the freshly re-registered bytes were never
     * uploaded - a changed texture file only took effect after a game restart,
     * and {@code TEXTURE_TRANSLUCENT} stayed stale too.
     */
    static void invalidateAll() {
        Set<String> textureKeys;
        synchronized (TEXTURE_TOKENS) {
            textureKeys = new java.util.HashSet<>(UPLOADED_TEXTURES.keySet());
            textureKeys.addAll(PENDING_TEXTURE_RELEASES.keySet());
            TEXTURE_TOKENS.clear();
            TEXTURE_DATA.clear();
            UPLOADED_TEXTURES.clear();
            TEXTURE_TRANSLUCENT.clear();
            TEXTURE_REFS.clear();
            MODEL_TEXTURES.clear();
            PENDING_TEXTURE_DECODES.clear();
            PENDING_TEXTURE_RELEASES.clear();
        }
        // Decoded but not yet uploaded images own native (off-heap) buffers.
        for (TextureUploadTask task = COMPLETED_UPLOADS.poll(); task != null; task = COMPLETED_UPLOADS.poll()) {
            closeQuietly(task.image());
        }
        for (String key : textureKeys) {
            try {
                Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(key));
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to release texture {} on reload", key, t);
            }
        }
    }

    /**
     * Register raw texture bytes (PNG/JPEG) under our own resource location,
     * ready for on-demand upload to the texture manager (see
     * ensureTextureUploaded), and mirror them into the generated pack.
     *
     * @param relativePath path inside our textures/ space (without .png); it is
     *                     sanitized, so a traversal form cannot escape it
     * @param data         encoded image bytes
     * @return the resource location the bytes were registered under
     */
    static ResourceLocation registerTextureBytes(String relativePath, byte[] data) {
        String safePath = GeneratedPackStore.sanitize(relativePath);
        // TLM's manifest already names the file with its extension (".../x.png"),
        // so appending another one produced ".../x.png.png": a resource location
        // whose path lies about the file it points at, and (with the pack mirror
        // below) a second copy of every texture under a doubled extension.
        if (safePath.length() > 4 && safePath.regionMatches(true, safePath.length() - 4, ".png", 0, 4)) {
            safePath = safePath.substring(0, safePath.length() - 4);
        }
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(GeneratedPackStore.MESH_NAMESPACE,
                "textures/" + safePath + ".png");
        String key = rl.toString();
        synchronized (TEXTURE_TOKENS) {
            TEXTURE_TOKENS.put(key, NEXT_TEXTURE_TOKEN.incrementAndGet());
            TEXTURE_DATA.put(key, data);
            PENDING_TEXTURE_DECODES.remove(key);
            UPLOADED_TEXTURES.remove(key);
            TEXTURE_TRANSLUCENT.remove(key);
            PENDING_TEXTURE_RELEASES.remove(key);
        }
        GeneratedPackStore.writePackTexture(safePath, data);
        return rl;
    }

    /**
     * Start uploading the texture bytes to the texture manager if not done yet.
     *
     * The decode runs on the background pool and the GL upload is drained on the
     * render thread by {@link #processPendingTextureUploads}, so the first draw
     * of a model does not block on a multi-megabyte PNG decode.
     *
     * Until the upload completes {@link #isTextureUploaded} stays false, which is
     * exactly the contract the direct draw paths already check: they fall back to
     * Epic Fight's own render path for those frames, which binds the texture
     * through the resource manager (the generated pack file, see
     * {@link GeneratedPackStore#writePackTexture}). So the model is textured from the first frame;
     * only the skinning path changes for a frame or two.
     */
    static void ensureTextureUploaded(ResourceLocation rl) {
        if (rl == null) {
            return;
        }
        String key = rl.toString();
        if (UPLOADED_TEXTURES.containsKey(key)) {
            return;
        }
        Long token = TEXTURE_TOKENS.get(key);
        if (token == null) {
            return;
        }
        byte[] data = TEXTURE_DATA.get(key);
        if (PENDING_TEXTURE_DECODES.putIfAbsent(key, token) != null) {
            return;
        }
        // a re-upload cancels any delayed release of the same location
        PENDING_TEXTURE_RELEASES.remove(key);
        DECODE_POOL.execute(() -> {
            NativeImage image = null;
            try {
                byte[] bytes = data;
                if (bytes == null) {
                    // The bytes are gone if the texture was released by an eviction
                    // (or uploaded in an earlier generation). The generated pack
                    // file written at registration time is the durable copy, so
                    // the texture is re-read and re-uploaded instead of leaving
                    // the model on the fallback path for the rest of the session.
                    bytes = GeneratedPackStore.readPackTextureBytes(rl);
                }
                if (bytes == null) {
                    return;
                }
                image = YSMTextureDecoder.decodeTexture(key, bytes);
                if (image == null) {
                    markTextureDecodeFailed(key, token, data);
                    return;
                }
                // The translucency scan runs here, on the decode worker: the
                // per-frame upload drain has a small time budget and must not
                // spend it scanning millions of pixels.
                boolean translucent = hasTranslucentPixels(image);
                if (isCurrentTexture(key, token)) {
                    COMPLETED_UPLOADS.add(new TextureUploadTask(rl, image, translucent, token));
                    image = null; // the queue owns the native buffer now
                    try {
                        Minecraft.getInstance().execute(YSMTextureRegistry::processPendingTextureUploads);
                    } catch (Throwable t) {
                        // The client tick also drains this queue, so the image is
                        // still owned and will be uploaded or closed there.
                        YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to schedule texture upload {}", key, t);
                    }
                }
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to decode texture {}", key, t);
                markTextureDecodeFailed(key, token, data);
            } finally {
                if (image != null) {
                    closeQuietly(image);
                }
                PENDING_TEXTURE_DECODES.remove(key, token);
            }
        });
    }

    static boolean isCurrentTexture(String key, long token) {
        return Long.valueOf(token).equals(TEXTURE_TOKENS.get(key));
    }

    private static void markTextureDecodeFailed(String key, long token, byte[] registeredBytes) {
        synchronized (TEXTURE_TOKENS) {
            if (isCurrentTexture(key, token)) {
                UPLOADED_TEXTURES.put(key, Boolean.TRUE);
                if (registeredBytes != null) {
                    TEXTURE_DATA.remove(key, registeredBytes);
                }
            }
        }
    }

    /**
     * Drain finished decodes into the texture manager, within a per-frame time
     * budget. Must run on the render thread. Leftovers stay queued and the drain
     * re-posts itself for the next frame.
     */
    static void processPendingTextureUploads() {
        if (COMPLETED_UPLOADS.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TEXTURE_UPLOAD_BUDGET_NANOS;
        while (true) {
            TextureUploadTask task = COMPLETED_UPLOADS.poll();
            if (task == null) {
                return;
            }
            String key = task.location().toString();
            if (!isCurrentTexture(key, task.token())) {
                // This resource was evicted or replaced after decoding.
                closeQuietly(task.image());
                continue;
            }
            DynamicTexture texture;
            try {
                texture = new DynamicTexture(task.image());
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to create texture {}", key, t);
                closeQuietly(task.image());
                continue;
            }
            try {
                Minecraft.getInstance().getTextureManager().register(task.location(), texture);
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to upload texture {}", key, t);
                texture.close();
                continue;
            }
            PENDING_TEXTURE_RELEASES.remove(key);
            TEXTURE_TRANSLUCENT.put(key, task.translucent());
            UPLOADED_TEXTURES.put(key, Boolean.TRUE);
            TEXTURE_DATA.remove(key);
            if (System.nanoTime() > deadline) {
                Minecraft.getInstance().execute(YSMTextureRegistry::processPendingTextureUploads);
                return;
            }
        }
    }

    private static void closeQuietly(NativeImage image) {
        try {
            image.close();
        } catch (Throwable ignored) {
            // closing a half-uploaded image must never mask the original failure
        }
    }

    /**
     * Whether the texture was fully uploaded to the texture manager (its GL
     * texture id is valid). The CPU skinning path binds the model texture
     * directly, so it must not draw before the upload completed.
     */
    static boolean isTextureUploaded(ResourceLocation rl) {
        return rl != null && UPLOADED_TEXTURES.containsKey(rl.toString());
    }

    /**
     * Whether the model texture has translucent pixels (any alpha below 253),
     * driving the CPU path's second (blended) draw pass. Unknown textures are
     * treated as opaque.
     */
    static boolean isTranslucentTexture(ResourceLocation rl) {
        if (rl == null) {
            return false;
        }
        return Boolean.TRUE.equals(TEXTURE_TRANSLUCENT.get(rl.toString()));
    }

    /**
     * One-time full scan of the decoded texture for translucent pixels
     * (alpha &lt; 253). Every pixel is checked, because a strided sampling
     * misses small translucent regions (hair strands, gradients) and the CPU
     * path's first pass then discards them (alphaMode == 1).
     */
    private static boolean hasTranslucentPixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) < 253) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Record that one model's mesh references this texture. Called from the
     * conversion path, which knows which texture belongs to which model.
     *
     * Textures are reference counted because model packs may share one: a pack's
     * extra_textures may point at another model's file, and both models then hand
     * out the same resource location. Eviction of one of them must not release a
     * texture the other is still rendering with.
     */
    static void bindModelTexture(String modelId, ResourceLocation texture) {
        if (modelId == null || texture == null) {
            return;
        }
        Set<String> textures = MODEL_TEXTURES.computeIfAbsent(modelId, k -> ConcurrentHashMap.newKeySet());
        String key = texture.toString();
        if (textures.add(key)) {
            synchronized (TEXTURE_TOKENS) {
                TEXTURE_REFS.merge(key, 1, Integer::sum);
                TEXTURE_TOKENS.computeIfAbsent(key, ignored -> NEXT_TEXTURE_TOKEN.incrementAndGet());
                PENDING_TEXTURE_RELEASES.remove(key);
            }
        }
    }

    /** Snapshot the generated-pack paths still referenced by converted models. */
    static Set<String> registeredPackTexturePaths() {
        Set<String> keep = new java.util.HashSet<>();
        for (Set<String> textures : MODEL_TEXTURES.values()) {
            for (String key : textures) {
                ResourceLocation rl = ResourceLocation.tryParse(key);
                if (rl != null && GeneratedPackStore.MESH_NAMESPACE.equals(rl.getNamespace())) {
                    keep.add(rl.getPath());
                }
            }
        }
        return keep;
    }

    /**
     * Drop one model's texture registrations and schedule their GL release a few
     * ticks later (a texture may still be bound by draws already recorded for
     * this frame).
     *
     * @return the released resource locations, for logging
     */
    static List<ResourceLocation> releaseTexturesOfModel(String modelId) {
        Set<String> textures = MODEL_TEXTURES.remove(modelId);
        if (textures == null || textures.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> released = new java.util.ArrayList<>();
        for (String key : textures) {
            Integer refs = TEXTURE_REFS.get(key);
            if (refs != null && refs > 1) {
                // another model still renders with this texture: drop only our
                // reference and leave the upload in place
                TEXTURE_REFS.put(key, refs - 1);
                continue;
            }
            synchronized (TEXTURE_TOKENS) {
                TEXTURE_TOKENS.remove(key);
                TEXTURE_REFS.remove(key);
                TEXTURE_DATA.remove(key);
                UPLOADED_TEXTURES.remove(key);
                TEXTURE_TRANSLUCENT.remove(key);
                PENDING_TEXTURE_DECODES.remove(key);
                PENDING_TEXTURE_RELEASES.put(key, YSMMeshLibrary.RELEASE_DELAY_TICKS);
            }
            try {
                released.add(ResourceLocation.parse(key));
            } catch (Throwable ignored) {
                // unparseable keys can only come from a corrupted table; the
                // release below is driven by the key string, so it still happens
            }
        }
        return released;
    }

    /**
     * Release textures whose delay elapsed. Called from the client tick, which
     * runs on the render thread - the texture manager may only be touched there.
     */
    static void processPendingTextureReleases() {
        for (java.util.Iterator<Map.Entry<String, Integer>> it = PENDING_TEXTURE_RELEASES.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            it.remove();
            try {
                Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(entry.getKey()));
            } catch (Throwable ignored) {
                // a texture already released (or never registered) is not an error
            }
        }
    }
}
