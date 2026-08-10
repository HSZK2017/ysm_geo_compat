package com.ysmef.geomodel.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.runtime.YSMRuntimeModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");

    private static final String MESH_NAMESPACE = YSMGeoCompat.MODID;

    /** textureRL string -> png bytes (registered into the texture manager on demand) */
    private static final Map<String, byte[]> TEXTURE_DATA = new LinkedHashMap<>();

    /** textureRL string -> true once registered in the texture manager */
    private static final Map<String, Boolean> UPLOADED_TEXTURES = new ConcurrentHashMap<>();

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
        return RUNTIME_DIR.resolve(meshId + ".json");
    }

    /** modelId -> meshId (sanitized), for runtime lookup. */
    public static String meshIdOf(String modelId) {
        return sanitize(modelId);
    }

    /**
     * The mesh file name used for TLM model-pack meshes: "namespace__path"
     * (mirrors the naming in TlmModelLibrary).
     */
    public static String tlmMeshIdOf(String modelId) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(modelId);
        if (rl == null) {
            return null;
        }
        return tlmSanitize(rl.getNamespace()) + "__" + tlmSanitize(rl.getPath());
    }

    private static String tlmSanitize(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Ensure the generated resource pack skeleton exists (called before the pack
     * repository is built).
     */
    public static void preparePackFolder() {
        try {
            Files.createDirectories(MESH_DIR);
            Files.createDirectories(RUNTIME_DIR);
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

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        boolean stripped = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
                stripped = true;
            }
        }
        if (stripped) {
            sb.append('_').append(Integer.toHexString(value.hashCode()));
        }
        return sb.toString();
    }

    /**
     * Register raw texture bytes (PNG/JPEG) under our own resource location,
     * ready for on-demand upload to the texture manager (see
     * ensureTextureUploaded).
     *
     * @param relativePath path inside our textures/ space (without .png)
     * @param data         encoded image bytes
     * @return the resource location the bytes were registered under
     */
    public static ResourceLocation registerTextureBytes(String relativePath, byte[] data) {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                "textures/" + relativePath + ".png");
        TEXTURE_DATA.put(rl.toString(), data);
        return rl;
    }

    /**
     * Upload the texture bytes to the texture manager if not done yet.
     * Must be called on the render thread.
     */
    public static void ensureTextureUploaded(ResourceLocation rl) {
        if (rl == null || UPLOADED_TEXTURES.containsKey(rl.toString())) {
            return;
        }
        byte[] data = TEXTURE_DATA.get(rl.toString());
        if (data == null) {
            return;
        }
        try {
            NativeImage image = decodeTexture(rl, data);
            if (image == null) {
                UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
                return;
            }
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to upload texture {}", rl, t);
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        }
    }

    /**
     * Decode texture bytes into a NativeImage (PNG/JPEG encoded data).
     *
     * Uses the InputStream-based read: NativeImage.read(byte[]) copies the whole
     * array onto the 64KB LWJGL MemoryStack, which overflows for large textures
     * ("Out of stack space"), while the InputStream overload buffers off-heap.
     */
    private static NativeImage decodeTexture(ResourceLocation rl, byte[] data) throws IOException {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: unsupported texture format for {}", rl);
        return null;
    }

    /**
     * Submit the runtime script compilation of one model to the background pool
     * (used by TLM model registration), so the first draw finds the compiled
     * scripts instead of compiling (potentially ~100ms for big models) on the
     * render thread.
     */
    public static void preloadRuntimeAsync(String modelId) {
        PRELOAD_POOL.submit(() -> YSMRuntimeModel.preload(modelId));
    }
}
