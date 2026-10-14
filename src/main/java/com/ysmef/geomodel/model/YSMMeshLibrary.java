package com.ysmef.geomodel.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.runtime.YSMRuntimeModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Meshes;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/**
 * Shared infrastructure for the generated Epic Fight meshes of Touhou Little
 * Maid model packs (see {@link TlmModelLibrary}):
 *
 * - the generated resource pack folder (config/ysm_geo_compat/resourcepack,
 *   registered as a client resource pack) where the converted animmodels mesh
 *   JSONs and ysm_runtime script JSONs live
 * - the texture byte registry: TLM textures are registered under our own
 *   resource locations and uploaded to the texture manager on demand
 * - the background pool that compiles runtime scripts off the render thread
 *
 * Note: this mod deliberately keeps no YSM-model support (no config/yes_steve_model
 * scanning, no .ysm decryption). YSM models - for players and for maids using a
 * YSM model - are handled by the YSM_EpicFight_Compat mod when installed.
 */
public final class YSMMeshLibrary {

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_geo_compat");
    private static final Path PACK_ROOT = CONFIG_ROOT.resolve("resourcepack");
    private static final Path MESH_DIR = PACK_ROOT.resolve("assets").resolve(YSMGeoCompat.MODID).resolve("animmodels").resolve("entity");
    private static final Path RUNTIME_DIR = PACK_ROOT.resolve("assets").resolve(YSMGeoCompat.MODID).resolve("ysm_runtime").resolve("entity");
    private static final Path TEXTURE_DIR = PACK_ROOT.resolve("assets").resolve(YSMGeoCompat.MODID).resolve("textures");
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");

    private static final String MESH_NAMESPACE = YSMGeoCompat.MODID;

    /**
     * textureRL string -> original encoded texture bytes, until the texture is
     * uploaded (registered from the conversion path, drained on the render
     * thread). Concurrent because the conversion runs off-thread.
     */
    private static final Map<String, byte[]> TEXTURE_DATA = new ConcurrentHashMap<>();

    /**
     * relative texture path -> content hash of the file already mirrored into the
     * generated pack, so a reload that re-registers unchanged textures does not
     * rewrite them (see writePackTexture).
     */
    private static final Map<String, Integer> PACK_TEXTURE_HASHES = new ConcurrentHashMap<>();

    /**
     * OpenYSM/ModernYSM ship the ImageStream decoders (WebP/AVIF) inside their
     * jar (jar-in-jar). This mod has no YSM dependency, so the classes are
     * looked up reflectively at runtime; null when YSM is absent.
     */
    private static final Class<?> YSM_WEBP_DECODER_CLASS = findImageStreamDecoder("rip.ysm.imagestream.webp.WebpDecoder");
    private static final Class<?> YSM_AVIF_DECODER_CLASS = findImageStreamDecoder("rip.ysm.imagestream.avif.AvifDecoder");
    private static final Map<Class<?>, Method> IMAGE_STREAM_READ_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> IMAGE_STREAM_CTORS = new ConcurrentHashMap<>();
    private static volatile boolean IMAGE_STREAM_MISSING_LOGGED = false;
    private static volatile boolean IMAGE_STREAM_FAILED_LOGGED = false;

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
    private static final Map<String, Long> TEXTURE_TOKENS = new ConcurrentHashMap<>();

    /** textureRL -> token of the decode currently owned by the background pool. */
    private static final Map<String, Long> PENDING_TEXTURE_DECODES = new ConcurrentHashMap<>();

    /** textureRL string -> ticks left before its GL texture is released. */
    private static final Map<String, Integer> PENDING_TEXTURE_RELEASES = new ConcurrentHashMap<>();

    /**
     * Ticks an evicted mesh/texture is kept alive after eviction.
     *
     * The mesh instance is shared by every entity using the model and may still
     * be drawn later in the current frame (shadow/outline/capture passes select
     * meshes before the main pass draws them), and a texture may still be bound
     * by a draw command already recorded. Destroying either immediately would
     * use freed GL objects; by the time the delay elapses the mesh has been
     * dropped from Epic Fight's cache and a fresh instance has taken over.
     */
    private static final int RELEASE_DELAY_TICKS = 5;

    /** Evicted meshes are no longer in Epic Fight's cache and must be destroyed on reload. */
    private static final DelayedReleaseQueue<YSMMesh> PENDING_MESH_RELEASES =
            new DelayedReleaseQueue<>(RELEASE_DELAY_TICKS, YSMMeshLibrary::releaseMesh);

    /**
     * Wall-clock budget for the per-frame texture upload drain (ModernYSM's
     * UploadManager uses the same idea). Uploads are GL calls and cannot be
     * spread across threads; bounding the time keeps a burst of first-seen
     * models from stalling one frame, at the cost of a few frames of
     * missing-texture rendering.
     */
    private static final long TEXTURE_UPLOAD_BUDGET_NANOS = 10_000_000L;

    /**
     * LRU usage tracking in access order: every mesh lookup touches its model.
     * When more models are loaded than the configured cap, the least recently
     * used ones release their GL resources (see {@link #trimIfNeeded}).
     */
    private static final java.util.LinkedHashMap<String, Boolean> ACCESS_ORDER =
            new java.util.LinkedHashMap<>(64, 0.75f, true);

    /** Models whose mesh was actually instantiated, i.e. own GL resources. */
    private static final Set<String> LOADED_MODELS = ConcurrentHashMap.newKeySet();

    /**
     * Set when Epic Fight's private mesh cache cannot be reached: eviction is
     * then disabled rather than performed partially. Dropping the entry from our
     * own tables while Epic Fight still holds the mesh would leave a dead
     * instance in a cache that keeps handing it out, and every later draw would
     * use freed GL objects - far worse than the memory eviction saves.
     */
    private static volatile boolean EVICTION_DISABLED = false;

    /** Whether the first eviction batch was already reported at info level. */
    private static volatile boolean EVICTION_LOGGED = false;

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
     * Background pool for runtime-script preloads (compiling a runtime model
     * takes ~100ms for large models; doing it off the render thread keeps the
     * first draw of a model free of hitches).
     */
    private static final ExecutorService PRELOAD_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "ysm-geo-preload");
                thread.setDaemon(true);
                return thread;
            });

    private YSMMeshLibrary() {}

    /**
     * Draw-path resource releasers, registered by the gpu/cpu packages on first
     * class load. Keeps the model package from importing them (see
     * {@link com.ysmef.geomodel.model.MeshReleaser}).
     */
    private static final List<MeshReleaser> RELEASERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerMeshReleaser(MeshReleaser releaser) {
        if (releaser != null) {
            RELEASERS.add(releaser);
        }
    }

    /** Free the draw-path resources of one mesh on every registered path. */
    public static void disposeMesh(YSMMesh mesh) {
        for (MeshReleaser releaser : RELEASERS) {
            try {
                releaser.disposeMesh(mesh);
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: mesh release failed", t);
            }
        }
    }

    /** Free every direct-draw resource (resource reload). */
    public static void disposeAllPaths() {
        for (MeshReleaser releaser : RELEASERS) {
            try {
                releaser.disposeAll();
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: render-path release failed", t);
            }
        }
    }

    /**
     * The resource pack root that should be registered as a client resource pack.
     */
    public static Path getPackRoot() {
        return PACK_ROOT;
    }

    /**
     * Directory inside the generated pack where Epic Fight animmodels mesh JSONs
     * live (assets/<modid>/animmodels/entity).
     */
    public static Path getMeshDir() {
        return MESH_DIR;
    }

    /**
     * The runtime script JSON (bone table + molang animations) generated for the
     * given mesh id, evaluated by YSMRuntimeModel at render time.
     */
    public static Path getRuntimeFile(String meshId) {
        // sanitize() guarantees a traversal-free relative path, so this resolve
        // cannot escape RUNTIME_DIR; resolveInside re-checks it as a fence in
        // front of the filesystem (the fallback is unreachable for sanitize
        // output and exists only to keep the method total).
        String name = sanitize(meshId);
        Path resolved = resolveInside(RUNTIME_DIR, name + ".json");
        return resolved != null ? resolved : RUNTIME_DIR.resolve(name + ".json");
    }

    /** modelId -> meshId (sanitized), for runtime lookup. */
    public static String meshIdOf(String modelId) {
        return sanitize(modelId);
    }

    /**
     * The mesh file name used for TLM model-pack meshes: "namespace__path"
     * (mirrors the naming in TlmModelLibrary). Both halves go through the same
     * {@link #sanitize} the writer uses, so the lookup can never drift from the
     * generated file name.
     */
    public static String tlmMeshIdOf(String modelId) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(modelId);
        if (rl == null) {
            return null;
        }
        return sanitize(rl.getNamespace()) + "__" + sanitize(rl.getPath());
    }

    /**
     * Drop every texture's registered bytes and upload state (resource reload).
     *
     * Without this the reload was a no-op for textures: {@code UPLOADED_TEXTURES}
     * still contained the resource location, so {@code ensureTextureUploaded}
     * returned immediately and the freshly re-registered bytes were never
     * uploaded - a changed texture file only took effect after a game restart,
     * and {@code TEXTURE_TRANSLUCENT} stayed stale too.
     */
    public static void invalidateAll() {
        // These meshes have already been removed from Epic Fight's cache. A
        // reload cannot hand their destruction back to Epic Fight.
        PENDING_MESH_RELEASES.releaseAll();
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
        LOADED_MODELS.clear();
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.clear();
        }
    }

    /**
     * Delete generated pack textures that no registered model refers to any more.
     *
     * Called once per mesh generation, after every model was converted and its
     * textures registered. Without this the pack accumulates the textures of
     * models that were removed from a pack, renamed, or registered under an
     * earlier naming scheme - leftovers that are never read again but stay in the
     * user's config folder forever.
     *
     * Does nothing when nothing was registered, so a failed generation can never
     * wipe the folder.
     */
    public static void deleteStalePackTextures() {
        if (MODEL_TEXTURES.isEmpty()) {
            return;
        }
        Set<String> keep = new java.util.HashSet<>();
        for (Set<String> textures : MODEL_TEXTURES.values()) {
            for (String key : textures) {
                ResourceLocation rl = ResourceLocation.tryParse(key);
                if (rl != null && MESH_NAMESPACE.equals(rl.getNamespace())) {
                    keep.add(rl.getPath());
                }
            }
        }
        if (keep.isEmpty()) {
            return;
        }
        try (var stream = Files.walk(TEXTURE_DIR)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = TEXTURE_DIR.relativize(path).toString().replace('\\', '/');
                if (!keep.contains("textures/" + relative)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // a file locked by another process stays; the next reload retries
                    }
                }
            });
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to clean the generated pack textures: {}",
                    e.toString());
        }
    }

    /**
     * Ensure the generated resource pack skeleton exists (called before the pack
     * repository is built).
     */
    public static void preparePackFolder() {
        try {
            Files.createDirectories(MESH_DIR);
            Files.createDirectories(RUNTIME_DIR);
            Files.createDirectories(TEXTURE_DIR);
            if (!Files.exists(PACK_META)) {
                Files.writeString(PACK_META, """
                        {
                            "pack": {
                                "description": "YSM-GEO Compat generated meshes",
                                "pack_format": 15
                            }
                        }
                        """, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            YSMGeoCompat.LOGGER.error("YSM-GEO Compat: failed to prepare generated pack folder", e);
        }
    }

    /**
     * Rewrite an untrusted, model-supplied id into a relative path that cannot
     * escape the directory it is resolved against.
     *
     * Model packs are third-party files. The previous version appended '.' and
     * '/' verbatim, so a manifest declaring {@code "model_id": "x:../../../../evil"}
     * produced an id that {@code Path.resolve} walked straight out of the
     * generated pack - an arbitrary file read (model/texture/animation loads)
     * and arbitrary write (generated mesh/runtime JSON) primitive.
     *
     * The rewrite therefore does three things:
     * - folds characters outside the resource-location alphabet to '_' and
     *   appends a hash suffix when anything was folded, so distinct ids stay
     *   distinct (failures can never silently collide);
     * - drops empty segments, which removes the leading/doubled-slash
     *   (absolute-path) form;
     * - replaces the traversal segments ".." and "." with "_".
     *
     * The result only ever contains {@code [a-z0-9/._-]} with non-empty,
     * non-traversal segments, i.e. it is safe to resolve against any root.
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean rewritten = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
                rewritten = true;
            }
        }
        String[] segments = sb.toString().split("/");
        sb.setLength(0);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals("..") || segment.equals(".")) {
                segment = "_";
                rewritten = true;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segment);
        }
        if (rewritten) {
            sb.append('_').append(Integer.toHexString(value.hashCode()));
        }
        return sb.toString();
    }

    /**
     * Resolve a model-supplied relative path against {@code root}, returning
     * null unless the result stays inside {@code root}.
     *
     * Rejects absolute paths, drive-letter and UNC forms, backslashes, NUL and
     * the "." / ".." segments, then re-checks the normalized result against the
     * normalized root. This is the lexical fence that every filesystem access
     * driven by a model pack must pass through; a symlink-based escape still
     * needs a {@code toRealPath} check, but no model pack can reach one without
     * passing this first.
     */
    public static Path resolveInside(Path root, String relative) {
        if (root == null || relative == null || relative.isEmpty()) {
            return null;
        }
        if (relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0 || relative.startsWith("/")) {
            return null;
        }
        if (relative.length() >= 2 && relative.charAt(1) == ':') {
            return null;
        }
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base) ? resolved : null;
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
    public static ResourceLocation registerTextureBytes(String relativePath, byte[] data) {
        String safePath = sanitize(relativePath);
        // TLM's manifest already names the file with its extension (".../x.png"),
        // so appending another one produced ".../x.png.png": a resource location
        // whose path lies about the file it points at, and (with the pack mirror
        // below) a second copy of every texture under a doubled extension.
        if (safePath.length() > 4 && safePath.regionMatches(true, safePath.length() - 4, ".png", 0, 4)) {
            safePath = safePath.substring(0, safePath.length() - 4);
        }
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
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
        writePackTexture(safePath, data);
        return rl;
    }

    /**
     * Write the texture into the generated pack so the resource location we hand
     * out actually resolves through the resource manager.
     *
     * Two consumers need this. Anything that loads a texture by resource
     * location - vanilla model layers, Epic Fight's own texture handling, other
     * mods - only finds it through the ResourceManager, and a DynamicTexture
     * registered with the texture manager is invisible to that path (it throws
     * FileNotFoundException, rendering the mesh with red/missing edges). And a
     * pack on disk is the only way a user can inspect or replace the texture of
     * a converted model.
     *
     * PNG/JPEG payloads are copied verbatim (the decoder sniffs the format by
     * magic bytes); WebP/AVIF payloads go through YSM's ImageStream decoders and
     * raw-RGBA payloads through {@link #readRawRgba}, both re-encoded to PNG -
     * writing the encoded bytes of those formats verbatim would produce a file
     * whose extension lies about its content.
     *
     * Runs off the render thread (registration happens during conversion), so
     * the write never stalls a frame.
     */
    private static void writePackTexture(String safeRelativePath, byte[] data) {
        if (safeRelativePath.isEmpty() || data == null || data.length == 0) {
            return;
        }
        int hash = Arrays.hashCode(data);
        Integer previous = PACK_TEXTURE_HASHES.get(safeRelativePath);
        if (previous != null && previous == hash) {
            // the same bytes were already written in this session; conversion is
            // re-run on every config/pack reload, and rewriting 235 identical
            // files each time would be pure disk churn
            return;
        }
        Path file = resolveInside(TEXTURE_DIR, safeRelativePath + ".png");
        if (file == null) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: refused to write pack texture outside the pack root: {}",
                    safeRelativePath);
            return;
        }
        try {
            if (isPng(data) || isJpeg(data)) {
                PackFileIO.writeAtomically(file, PACK_ROOT, tmp -> Files.write(tmp, data));
            } else {
                NativeImage image = decodeTexture(safeRelativePath, data);
                if (image == null) {
                    return;
                }
                try {
                    PackFileIO.writeAtomically(file, PACK_ROOT, image::writeToFile);
                } finally {
                    image.close();
                }
            }
            PACK_TEXTURE_HASHES.put(safeRelativePath, hash);
        } catch (Throwable t) {
            // A failed mirror is not fatal: ensureTextureUploaded still feeds the
            // DynamicTexture from TEXTURE_DATA, so the model renders textured.
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to write pack texture {}: {}",
                    safeRelativePath, t.toString());
        }
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
     * {@link #writePackTexture}). So the model is textured from the first frame;
     * only the skinning path changes for a frame or two.
     */
    public static void ensureTextureUploaded(ResourceLocation rl) {
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
                    bytes = readPackTextureBytes(rl);
                }
                if (bytes == null) {
                    return;
                }
                image = decodeTexture(key, bytes);
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
                        Minecraft.getInstance().execute(YSMMeshLibrary::processPendingTextureUploads);
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

    private static boolean isCurrentTexture(String key, long token) {
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
     * Re-read a texture's bytes from the generated pack file.
     *
     * Registration mirrors every texture into the pack (see
     * {@link #writePackTexture}), so the file is the durable copy of a texture
     * whose in-memory bytes were dropped after upload or released by an eviction.
     * Returns null when the pack has no such file (an unsupported format that
     * could not be re-encoded, or a deleted pack), which leaves the caller - and
     * the renderer - on the fallback path.
     *
     * Runs on the decode pool: no caller is on the render thread.
     */
    private static byte[] readPackTextureBytes(ResourceLocation rl) {
        if (rl == null || !MESH_NAMESPACE.equals(rl.getNamespace())) {
            return null;
        }
        try {
            Path file = resolveInside(PACK_ROOT.resolve("assets").resolve(MESH_NAMESPACE), rl.getPath());
            if (file == null || !Files.isRegularFile(file)
                    || !PackFileIO.isInsideReal(file, PACK_ROOT)) {
                return null;
            }
            return PackFileIO.readBytes(file);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to re-read texture {} from the pack: {}",
                    rl, t.toString());
            return null;
        }
    }

    /**
     * Drain finished decodes into the texture manager, within a per-frame time
     * budget. Must run on the render thread. Leftovers stay queued and the drain
     * re-posts itself for the next frame.
     */
    public static void processPendingTextureUploads() {
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
                Minecraft.getInstance().execute(YSMMeshLibrary::processPendingTextureUploads);
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
    public static boolean isTextureUploaded(ResourceLocation rl) {
        return rl != null && UPLOADED_TEXTURES.containsKey(rl.toString());
    }

    /**
     * Whether the model texture has translucent pixels (any alpha below 253),
     * driving the CPU path's second (blended) draw pass. Unknown textures are
     * treated as opaque.
     */
    public static boolean isTranslucentTexture(ResourceLocation rl) {
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

    // ------------------------------------------------------------------
    // Per-model lifecycle: usage tracking, eviction, delayed release
    // ------------------------------------------------------------------

    /**
     * Record that one model's mesh references this texture. Called from the
     * conversion path, which knows which texture belongs to which model.
     *
     * Textures are reference counted because model packs may share one: a pack's
     * extra_textures may point at another model's file, and both models then hand
     * out the same resource location. Eviction of one of them must not release a
     * texture the other is still rendering with.
     */
    public static void bindModelTexture(String modelId, ResourceLocation texture) {
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

    /** Record a model usage (LRU touch): the next trim evicts it last. */
    public static void touchModel(String modelId) {
        if (modelId == null || EVICTION_DISABLED || !LOADED_MODELS.contains(modelId)) {
            return;
        }
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.put(modelId, Boolean.TRUE);
        }
    }

    /** How many models the LRU currently tracks, i.e. hold GL resources. */
    public static int loadedModelCount() {
        synchronized (ACCESS_ORDER) {
            return ACCESS_ORDER.size();
        }
    }

    /** The configured resident-model cap. */
    public static int modelCacheCap() {
        return com.ysmef.geomodel.config.YSMCompatConfig.LAZY_MODEL_CACHE_SIZE.get();
    }

    /**
     * Record that a model's mesh was actually instantiated: from here on it owns
     * GL resources and becomes a candidate for eviction.
     */
    public static void markMeshLoaded(String modelId) {
        if (modelId == null) {
            return;
        }
        String meshOwnerId = TlmModelLibrary.meshOwnerOf(modelId);
        if (LOADED_MODELS.add(meshOwnerId)) {
            TlmModelLibrary.bindTexturesOfMesh(meshOwnerId);
            // Eviction also drops the compiled runtime model. Claim its preload
            // before this mesh is drawn so get() cannot compile it in the pass.
            preloadRuntimeAsync(meshOwnerId);
        }
        touchModel(meshOwnerId);
    }

    /**
     * Release the GL resources of the least recently used models while more than
     * the configured cap are loaded. Must run on the render thread.
     *
     * Eviction is self-healing because Epic Fight's {@code MeshAccessor.get()}
     * checks its mesh cache on every call and rebuilds the mesh from the
     * generated JSON when the entry is gone - so the next draw of an evicted maid
     * transparently re-loads her mesh. The generated JSON stays on disk, so
     * nothing is re-converted and no model is permanently degraded.
     */
    public static void trimIfNeeded() {
        if (EVICTION_DISABLED) {
            return;
        }
        int cap = com.ysmef.geomodel.config.YSMCompatConfig.LAZY_MODEL_CACHE_SIZE.get();
        List<String> victims;
        synchronized (ACCESS_ORDER) {
            if (ACCESS_ORDER.size() <= cap) {
                return;
            }
            // Oldest entries first; the just-touched models are at the tail.
            victims = new java.util.ArrayList<>(ACCESS_ORDER.keySet());
        }
        int evicted = 0;
        for (String victim : victims) {
            if (EVICTION_DISABLED) {
                // The mesh cache turned out to be unreachable: stop immediately so
                // the remaining victims keep their meshes AND their textures.
                break;
            }
            int loaded;
            synchronized (ACCESS_ORDER) {
                loaded = ACCESS_ORDER.size();
            }
            if (loaded <= cap) {
                break;
            }
            synchronized (ACCESS_ORDER) {
                ACCESS_ORDER.remove(victim);
            }
            if (evictModel(victim)) {
                evicted++;
            }
        }
        if (evicted > 0) {
            // Once per session at info (the log line is the only evidence the LRU
            // runs at all); the repeats stay on debug so a small cap does not
            // flood the log while the player moves between models.
            if (EVICTION_LOGGED) {
                YSMGeoCompat.LOGGER.debug("YSM-GEO Compat: evicted {} least recently used models (cap {})",
                        evicted, cap);
            } else {
                EVICTION_LOGGED = true;
                YSMGeoCompat.LOGGER.info(
                        "YSM-GEO Compat: evicted {} least recently used models (cap {}); "
                                + "they re-load from the generated mesh JSON on their next draw",
                        evicted, cap);
            }
        }
    }

    /**
     * Release one model's GL resources: its mesh instance, its textures and its
     * compiled runtime scripts. Returns true when the model's mesh was released.
     *
     * The mesh instance is taken out of Epic Fight's own mesh cache first. That
     * cache has no removal API, so reaching it takes reflection; when it cannot
     * be reached the eviction is abandoned entirely (see EVICTION_DISABLED)
     * rather than leaving a destroyed mesh behind in a cache that keeps handing
     * it out. Nothing is released on any failure path: a model that keeps its
     * mesh must keep its textures too.
     */
    private static boolean evictModel(String modelId) {
        if (!LOADED_MODELS.remove(modelId)) {
            // Never instantiated (or already evicted): the model owns no GL
            // resources, so there is nothing to release and its textures stay
            // warm for the next lookup.
            return false;
        }
        Meshes.MeshAccessor<YSMMesh> accessor = TlmModelLibrary.accessorOf(modelId);
        if (accessor == null) {
            // Not registered any more (the registry was replaced): its mesh went
            // with it, and the textures are re-read from the pack on next use.
            releaseTexturesOfModel(modelId);
            TlmModelLibrary.invalidateRuntimeModelsOfMesh(modelId);
            return false;
        }
        YSMMesh mesh = takeFromEfMeshCache(accessor);
        if (mesh == null) {
            // Either Epic Fight already dropped the instance (its own reload) or
            // the cache is unreachable; in both cases the model keeps its textures
            // rather than losing them for nothing.
            if (EVICTION_DISABLED) {
                // The accessor may still own its mesh; keep the residency count
                // so prewarming stops at the cap even without eviction support.
                LOADED_MODELS.add(modelId);
                synchronized (ACCESS_ORDER) {
                    ACCESS_ORDER.put(modelId, Boolean.TRUE);
                }
            }
            return false;
        }
        scheduleMeshRelease(mesh);
        releaseTexturesOfModel(modelId);
        TlmModelLibrary.invalidateRuntimeModelsOfMesh(modelId);
        return true;
    }

    /**
     * Remove the accessor's loaded mesh from Epic Fight's private static mesh
     * cache ({@code Meshes.MESHES}, keyed by accessor, no public removal API) and
     * return it.
     *
     * Returns null when the cache holds no entry for the accessor. Disables
     * eviction for the rest of the session and returns null when the cache cannot
     * be reached at all: the caller must then not release anything, because Epic
     * Fight would keep drawing the destroyed instance.
     */
    @SuppressWarnings("unchecked")
    private static YSMMesh takeFromEfMeshCache(Meshes.MeshAccessor<?> accessor) {
        if (EVICTION_DISABLED) {
            return null;
        }
        try {
            java.lang.reflect.Field field = Meshes.class.getDeclaredField("MESHES");
            field.setAccessible(true);
            Object map = field.get(null);
            if (!(map instanceof Map<?, ?>)) {
                throw new IllegalStateException("not a map");
            }
            Object removed = ((Map<Object, Object>) map).remove(accessor);
            return removed instanceof YSMMesh mesh ? mesh : null;
        } catch (Throwable t) {
            if (!EVICTION_DISABLED) {
                EVICTION_DISABLED = true;
                YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: Epic Fight's mesh cache is unreachable ({}); "
                                + "model eviction is disabled for this session", t.toString());
            }
            return null;
        }
    }

    /**
     * Queue one evicted mesh for the delayed release. Must be called on the
     * render thread; the GL deletions happen {@link #RELEASE_DELAY_TICKS} ticks
     * later (see {@link #processPendingMeshReleases}).
     */
    public static void scheduleMeshRelease(YSMMesh mesh) {
        if (mesh != null) {
            PENDING_MESH_RELEASES.schedule(mesh);
        }
    }

    /**
     * Drop one model's texture registrations and schedule their GL release a few
     * ticks later (a texture may still be bound by draws already recorded for
     * this frame).
     *
     * @return the released resource locations, for logging
     */
    public static List<ResourceLocation> releaseTexturesOfModel(String modelId) {
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
                PENDING_TEXTURE_RELEASES.put(key, RELEASE_DELAY_TICKS);
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
    public static void processPendingTextureReleases() {
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

    /**
     * Destroy evicted meshes whose delay elapsed. Called from the client tick:
     * the GL deletions require the render thread.
     */
    public static void processPendingMeshReleases() {
        PENDING_MESH_RELEASES.processTick();
    }

    private static void releaseMesh(YSMMesh mesh) {
        try {
            mesh.destroy();
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to destroy evicted mesh", t);
        }
        try {
            disposeMesh(mesh);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to release evicted mesh draw paths", t);
        }
    }

    /**
     * The per-tick lifecycle entry point (client tick, render thread): drains the
     * upload queue, runs the delayed releases and trims the model cache.
     *
     * Everything here is deferred work that must not run inside a draw: GL
     * uploads/releases are bounded to a small budget per frame, and the eviction
     * cap is enforced between frames.
     */
    public static void tick() {
        processPendingTextureUploads();
        processPendingTextureReleases();
        processPendingMeshReleases();
        trimIfNeeded();
    }

    /**
     * Decode texture bytes into a NativeImage, supporting PNG/JPEG encoded data,
     * WebP/AVIF (through YSM's ImageStream, reflectively) and raw RGBA pixels
     * (legacy .ysm binary textures).
     *
     * Uses the InputStream-based read: NativeImage.read(byte[]) copies the whole
     * array onto the 64KB LWJGL MemoryStack, which overflows for large textures
     * ("Out of stack space"), while the InputStream overload buffers off-heap.
     */
    private static NativeImage decodeTexture(String label, byte[] data) throws IOException {
        if (data.length == 0) {
            // An empty array would satisfy the raw-RGBA heuristic below
            // (0 % 4 == 0 and 0*0 == 0) and produce a 0x0 DynamicTexture that is
            // then marked "uploaded" forever.
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: empty texture data for {}", label);
            return null;
        }
        if (isPng(data)) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (isJpeg(data)) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (isRiffWebp(data)) {
            return decodeWithImageStream(YSM_WEBP_DECODER_CLASS, data, "WebP");
        }
        if (isFtypAvif(data)) {
            return decodeWithImageStream(YSM_AVIF_DECODER_CLASS, data, "AVIF");
        }
        if (data.length % 4 == 0) {
            int pixels = data.length / 4;
            int side = (int) Math.round(Math.sqrt(pixels));
            if ((long) side * side == pixels) {
                return readRawRgba(data, side, side);
            }
        }
        YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: unsupported texture format for {}", label);
        return null;
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50
                && data[2] == 0x4E && data[3] == 0x47;
    }

    private static boolean isJpeg(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    /**
     * Interpret the bytes as raw RGBA pixels (YSM legacy texture format) and
     * build a NativeImage (Minecraft packs pixels as ABGR).
     */
    private static NativeImage readRawRgba(byte[] data, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height * 4 > data.length) {
            int side = (int) Math.round(Math.sqrt(data.length / 4.0));
            width = side;
            height = side;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int r = data[i] & 0xFF;
                int g = data[i + 1] & 0xFF;
                int b = data[i + 2] & 0xFF;
                int a = data[i + 3] & 0xFF;
                image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return image;
    }

    private static Class<?> findImageStreamDecoder(String className) {
        try {
            return Class.forName(className, false, YSMMeshLibrary.class.getClassLoader());
        } catch (Throwable ignored) {
            try {
                ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
                return contextLoader != null
                        ? Class.forName(className, false, contextLoader)
                        : Class.forName(className);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static boolean isRiffWebp(byte[] data) {
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static boolean isFtypAvif(byte[] data) {
        return data.length >= 12
                && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
    }

    /**
     * Decode WebP/AVIF with OpenYSM/ModernYSM's ImageStream decoders
     * (rip.ysm.imagestream.*). Reflection keeps this mod free of a YSM compile
     * dependency; when YSM is absent the texture is skipped with one warning.
     */
    private static NativeImage decodeWithImageStream(Class<?> decoderClass, byte[] data, String formatName) {
        if (decoderClass == null) {
            try {
                BufferedImage imageIoImage = ImageIO.read(new ByteArrayInputStream(data));
                if (imageIoImage != null) {
                    return bufferedImageToNative(imageIoImage);
                }
            } catch (Throwable ignored) {
            }
            if (!IMAGE_STREAM_MISSING_LOGGED) {
                IMAGE_STREAM_MISSING_LOGGED = true;
                YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: {} texture found but the YSM ImageStream decoder is not available; "
                                + "the model will render without this texture", formatName);
            }
            return null;
        }
        try {
            Method read = IMAGE_STREAM_READ_METHODS.get(decoderClass);
            if (read == null) {
                for (Method candidate : decoderClass.getMethods()) {
                    if ("read".equals(candidate.getName())
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == byte[].class
                            && BufferedImage.class.isAssignableFrom(candidate.getReturnType())) {
                        read = candidate;
                        break;
                    }
                }
                if (read == null) {
                    if (!IMAGE_STREAM_FAILED_LOGGED) {
                        IMAGE_STREAM_FAILED_LOGGED = true;
                        YSMGeoCompat.LOGGER.warn(
                                "YSM-GEO Compat: cannot find read(byte[]) on {}; {} textures will be skipped",
                                decoderClass.getName(), formatName);
                    }
                    return null;
                }
                IMAGE_STREAM_READ_METHODS.put(decoderClass, read);
            }
            Constructor<?> ctor = IMAGE_STREAM_CTORS.get(decoderClass);
            if (ctor == null) {
                ctor = decoderClass.getDeclaredConstructor();
                IMAGE_STREAM_CTORS.put(decoderClass, ctor);
            }
            Object image = read.invoke(ctor.newInstance(), (Object) data);
            return image instanceof BufferedImage bufferedImage
                    ? bufferedImageToNative(bufferedImage)
                    : null;
        } catch (Throwable t) {
            if (!IMAGE_STREAM_FAILED_LOGGED) {
                IMAGE_STREAM_FAILED_LOGGED = true;
                YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: failed to decode {} texture with {}", formatName, decoderClass.getName(), t);
            }
            return null;
        }
    }

    /** Convert an ImageStream BufferedImage to Minecraft's ABGR NativeImage. */
    private static NativeImage bufferedImageToNative(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int a = (pixel >>> 24) & 0xFF;
                int r = (pixel >>> 16) & 0xFF;
                int g = (pixel >>> 8) & 0xFF;
                int b = pixel & 0xFF;
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

    /**
     * Submit the runtime script compilation of one model to the background pool
     * (used by TLM model registration), so the first draw finds the compiled
     * scripts instead of compiling (potentially ~100ms for big models) on the
     * render thread.
     */
    public static void preloadRuntimeAsync(String modelId) {
        YSMRuntimeModel.preloadAsync(modelId, PRELOAD_POOL);
    }
}
