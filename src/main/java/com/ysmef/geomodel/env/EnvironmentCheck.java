package com.ysmef.geomodel.env;

import com.ysmef.geomodel.YSMGeoCompat;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Startup diagnostics only; render-path capability checks remain authoritative. */
public final class EnvironmentCheck {
    private static final String DISABLE_FLAG = "ysm_geo_compat.disable_env_check";
    private static final long LOW_HEAP_BYTES = 1536L * 1024 * 1024;
    private static final Set<String> BIT32_ARCHES = Set.of("arm", "armv7l", "i386", "i486", "i586", "i686", "x86");
    private static final String[] ANDROID_ENV_PREFIXES = {"POJAV_", "FCL_", "ZALITH_", "TERMUX_"};

    private EnvironmentCheck() {}

    /** Safe during client setup before a graphics context is available. */
    public static void reportHost() {
        if (Boolean.getBoolean(DISABLE_FLAG)) return;
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        long heap = Runtime.getRuntime().maxMemory();
        YSMGeoCompat.LOGGER.info("YSM-GEO Compat: environment: os={} arch={} java={} ({}) maxHeap={}MiB",
                os, arch, System.getProperty("java.version", "?"),
                System.getProperty("java.vm.name", "?"), heap / (1024 * 1024));
        for (String warning : hostWarnings(os, arch, System.getProperty("java.home", ""),
                System.getProperty("user.home", ""), System.getenv().keySet(), heap)) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: {}", warning);
        }
    }

    /** Must run on the render thread after the GL context exists. */
    public static void reportGlContext() {
        if (Boolean.getBoolean(DISABLE_FLAG)) return;
        try {
            String version = GL11.glGetString(GL11.GL_VERSION);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            YSMGeoCompat.LOGGER.info("YSM-GEO Compat: GL context: version=\"{}\" renderer=\"{}\" vendor=\"{}\"",
                    version, renderer, vendor);
            for (String warning : glWarnings(version, renderer)) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: {}", warning);
            }
        } catch (Throwable error) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: GL environment probe unavailable ({})", error.toString());
        }
    }

    static List<String> hostWarnings(String osName, String osArch, String javaHome,
                                     String userHome, Set<String> envNames, long heapBytes) {
        List<String> warnings = new ArrayList<>();
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean android = startsInAndroidSandbox(javaHome) || startsInAndroidSandbox(userHome);
        if (envNames != null) {
            for (String name : envNames) {
                if (name == null) continue;
                for (String prefix : ANDROID_ENV_PREFIXES) android |= name.startsWith(prefix);
            }
        }
        if (android) {
            warnings.add("Android launcher detected; this mod's GL paths are tested on desktop. Include the GL context line when reporting rendering problems.");
        } else if (os.contains("linux") && arch.contains("arm")) {
            warnings.add("Linux/ARM environment detected; graphics compatibility may differ from tested desktop systems.");
        }
        if (BIT32_ARCHES.contains(arch)) {
            warnings.add("32-bit JVM detected; Minecraft, Epic Fight and converted models can exhaust its address space. Use a 64-bit JVM.");
        }
        if (heapBytes > 0 && heapBytes < LOW_HEAP_BYTES) {
            warnings.add("JVM max heap is " + heapBytes / (1024 * 1024)
                    + " MiB; model conversion may run out of memory below about 1536 MiB.");
        }
        return warnings;
    }

    static List<String> glWarnings(String glVersion, String glRenderer) {
        List<String> warnings = new ArrayList<>();
        if (glVersion == null) {
            warnings.add("GL version unavailable; cannot verify the desktop OpenGL 3.3 / OpenGL ES 3.0 minimum.");
            return warnings;
        }
        String version = glVersion.toLowerCase(Locale.ROOT);
        String renderer = glRenderer == null ? "" : glRenderer.toLowerCase(Locale.ROOT);
        boolean es = version.startsWith("opengl es");
        int[] parsed = parseVersion(es ? glVersion.substring("OpenGL ES".length()) : glVersion);
        if (es) {
            warnings.add("OpenGL ES context detected; mobile rendering support is best effort.");
            if (parsed == null || parsed[0] < 3) {
                warnings.add("OpenGL ES 3.0 is required for the CPU skinning path; converted maid models may not render.");
            } else if (parsed[0] == 3 && parsed[1] < 1) {
                warnings.add("OpenGL ES 3.1 is required for GPU skinning; the CPU path will be used when available.");
            }
        } else if (parsed != null && (parsed[0] < 3 || parsed[0] == 3 && parsed[1] < 3)) {
            warnings.add("Desktop OpenGL 3.3 is required for the CPU skinning path; converted maid models may not render.");
        }
        if (renderer.contains("gl4es") || version.contains("gl4es")) {
            warnings.add("gl4es wrapper detected; its GL support may be below the skinning shader requirements.");
        }
        if (renderer.contains("angle") || renderer.contains("zink") || renderer.contains("virgl")) {
            warnings.add("Translated or virtual GL renderer detected (" + glRenderer + "); include this line when reporting rendering problems.");
        }
        if (renderer.contains("llvmpipe") || renderer.contains("softpipe")
                || renderer.contains("swrast") || renderer.contains("swiftshader")) {
            warnings.add("Software GL renderer detected (" + glRenderer + "); model skinning may be very slow.");
        }
        return warnings;
    }

    private static boolean startsInAndroidSandbox(String path) {
        return path != null && path.startsWith("/data/");
    }

    static int[] parseVersion(String text) {
        if (text == null) return null;
        String value = text.trim();
        int end = 0;
        while (end < value.length() && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) end++;
        String[] parts = value.substring(0, end).split("\\.");
        try {
            return new int[]{Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0};
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
