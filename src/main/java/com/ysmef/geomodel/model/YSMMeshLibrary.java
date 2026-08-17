package com.ysmef.geomodel.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.runtime.YSMRuntimeModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");

    private static final String MESH_NAMESPACE = YSMGeoCompat.MODID;

    /** textureRL string -> original encoded texture bytes (registered into the texture manager on demand) */
    private static final Map<String, byte[]> TEXTURE_DATA = new LinkedHashMap<>();

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
            // One-time full translucency scan while the image is still in memory:
            // the CPU skinning path needs to know whether a translucent second
            // (blended) draw pass is required (see isTranslucentTexture).
            TEXTURE_TRANSLUCENT.put(rl.toString(), hasTranslucentPixels(image));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to upload texture {}", rl, t);
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
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

    /**
     * Decode texture bytes into a NativeImage, supporting PNG/JPEG encoded data,
     * WebP/AVIF (through YSM's ImageStream, reflectively) and raw RGBA pixels
     * (legacy .ysm binary textures).
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
        YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: unsupported texture format for {}", rl);
        return null;
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
        PRELOAD_POOL.submit(() -> YSMRuntimeModel.preload(modelId));
    }
}
