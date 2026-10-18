package com.ysmef.geomodel.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.geomodel.YSMGeoCompat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** Decodes generated-model textures without depending on a YSM mod at compile time. */
final class YSMTextureDecoder {

    private YSMTextureDecoder() {}

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

    /**
     * Decode texture bytes into a NativeImage, supporting PNG/JPEG encoded data,
     * WebP/AVIF (through YSM's ImageStream, reflectively) and raw RGBA pixels
     * (legacy .ysm binary textures).
     *
     * Uses the InputStream-based read: NativeImage.read(byte[]) copies the whole
     * array onto the 64KB LWJGL MemoryStack, which overflows for large textures
     * ("Out of stack space"), while the InputStream overload buffers off-heap.
     */
    static NativeImage decodeTexture(String label, byte[] data) throws IOException {
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

    static boolean isPng(byte[] data) {
        return data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50
                && data[2] == 0x4E && data[3] == 0x47;
    }

    static boolean isJpeg(byte[] data) {
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
            return Class.forName(className, false, YSMTextureDecoder.class.getClassLoader());
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
}
