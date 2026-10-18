package com.ysmef.geomodel.env;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentCheckTest {
    @Test
    void desktopEnvironmentDoesNotProduceCompatibilityWarnings() {
        assertTrue(EnvironmentCheck.hostWarnings("Windows 11", "amd64", "C:/Java", "C:/Users/test",
                Set.of("PATH"), 4L * 1024 * 1024 * 1024).isEmpty());
        assertTrue(EnvironmentCheck.glWarnings("4.6.0 NVIDIA", "NVIDIA RTX").isEmpty());
    }

    @Test
    void androidAndSmallHeapAreReportedWithoutChangingCapabilities() {
        var warnings = EnvironmentCheck.hostWarnings("Linux", "aarch64", "/data/user/0/java",
                "/data/user/0/home", Set.of("POJAV_VERSION"), 1024L * 1024 * 1024);
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Android launcher")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("1024 MiB")));
        assertTrue(EnvironmentCheck.hostWarnings("Linux", "x86", "/usr/lib/jvm", "/home/test",
                Set.of(), 2L * 1024 * 1024 * 1024).stream().anyMatch(s -> s.contains("32-bit")));
    }

    @Test
    void graphicsWarningsUseTheActualSkinningVersionFloors() {
        assertTrue(EnvironmentCheck.glWarnings("OpenGL ES 2.0", "gl4es").stream()
                .anyMatch(s -> s.contains("OpenGL ES 3.0")));
        assertTrue(EnvironmentCheck.glWarnings("OpenGL ES 3.0", "ANGLE").stream()
                .anyMatch(s -> s.contains("OpenGL ES 3.1")));
        assertTrue(EnvironmentCheck.glWarnings("3.2 Mesa", "llvmpipe").stream()
                .anyMatch(s -> s.contains("OpenGL 3.3")));
        assertTrue(EnvironmentCheck.glWarnings("4.6 Mesa", "llvmpipe").stream()
                .anyMatch(s -> s.contains("Software GL")));
        assertEquals(0, EnvironmentCheck.glWarnings("OpenGL ES 3.1", "Adreno").stream()
                .filter(s -> s.contains("required")).count());
    }
}
