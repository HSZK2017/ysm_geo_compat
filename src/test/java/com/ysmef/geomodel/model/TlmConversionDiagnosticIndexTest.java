package com.ysmef.geomodel.model;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmConversionDiagnosticIndexTest {
    @Test
    void declaredBaseAndVariantFailuresClearAsTheirMeshesBecomeAvailable() {
        var entry = JsonParser.parseString("""
                {"model_id":"maid:alice","extra_textures":["maid:textures/blue.png"]}
                """).getAsJsonObject();
        String variant = "maid:alice_" + TlmPackCatalog.variantHash(
                ResourceLocation.parse("maid:textures/blue.png"));
        Map<String, String> failures = new HashMap<>();
        Map<String, TlmModelLibrary.TlmMeshEntry> built = new HashMap<>();

        TlmModelLibrary.recordConversion(failures, built, entry, "maids.zip");
        assertTrue(failures.get("maid:alice").contains("maids.zip"));
        assertTrue(failures.get(variant).contains("extra texture"));

        built.put("maid:alice", null);
        TlmModelLibrary.recordConversion(failures, built, entry, "maids.zip");
        assertFalse(failures.containsKey("maid:alice"));
        assertTrue(failures.containsKey(variant));

        built.put(variant, null);
        TlmModelLibrary.recordConversion(failures, built, entry, "maids.zip");
        assertFalse(failures.containsKey(variant));
    }
}
