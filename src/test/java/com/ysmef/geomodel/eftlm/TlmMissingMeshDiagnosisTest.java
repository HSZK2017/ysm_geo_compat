package com.ysmef.geomodel.eftlm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmMissingMeshDiagnosisTest {
    @Test
    void pendingTransferDoesNotPrematurelyClaimThePackIsMissing() {
        assertNull(TlmMissingMeshDiagnosis.explain(true, false, null, null, List.of()));
    }

    @Test
    void fallbackDistinguishesConversionFailureUnreadableZipAndAbsentPackage() {
        String conversion = TlmMissingMeshDiagnosis.explain(false, false, "not found",
                "declared in maids.zip but geometry conversion failed", List.of());
        assertTrue(conversion.contains("mesh could not be built"));
        assertTrue(conversion.contains("maids.zip"));

        String unreadable = TlmMissingMeshDiagnosis.explain(false, false, "server has no pack",
                null, List.of("broken.zip"));
        assertTrue(unreadable.contains("broken.zip"));
        assertTrue(unreadable.contains("could not be scanned"));

        String absent = TlmMissingMeshDiagnosis.explain(false, false, "server has no pack",
                null, List.of());
        assertTrue(absent.contains("package is missing"));
        assertTrue(absent.contains("server has no pack"));
        assertTrue(absent.contains("tlm_custom_pack"));
    }
}
