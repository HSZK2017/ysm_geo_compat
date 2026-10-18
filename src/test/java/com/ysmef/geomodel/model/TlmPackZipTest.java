package com.ysmef.geomodel.model;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmPackZipTest {
    @TempDir Path temp;

    @Test
    void discoversNamespacedManifestsAndReadsReferencedAssets() throws IOException {
        Path path = temp.resolve("maids.zip");
        byte[] texture = {1, 2, 3, 4};
        try (ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            add(out, "assets/maid/maid_model.json", "{\"model_list\":[{\"model_id\":\"maid:alice\"}]}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/other/maid_model.json", "{\"model_list\":[]}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/models/entity/alice.json", "{\"format_version\":\"1.12.0\"}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/animations/alice.json", "{\"animations\":{}}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/textures/alice.png", texture);
            add(out, "assets/maid/textures/variant.png", new byte[]{5});
            add(out, "assets/maid/sub/maid_model.json", new byte[]{0});
            add(out, "assets/../maid_model.json", new byte[]{0});
        }

        assertTrue(TlmPackZip.isZipPack(path));
        try (ZipFile zip = new ZipFile(path.toFile())) {
            assertEquals(Set.of("maid", "other"), TlmPackZip.namespaces(zip));
            assertEquals("maid:alice", TlmPackZip.modelList(zip, "maid")
                    .get(0).getAsJsonObject().get("model_id").getAsString());
            assertEquals(0, TlmPackZip.modelList(zip, "other").size());
            try (InputStream in = TlmPackZip.open(zip, id("maid", "maid_model.json"))) {
                assertTrue(PackFileIO.readString(in).contains("maid:alice"));
            }
            try (InputStream in = TlmPackZip.open(zip, id("maid", "models/entity/alice.json"))) {
                assertTrue(PackFileIO.readString(in).contains("format_version"));
            }
            try (InputStream in = TlmPackZip.open(zip, id("maid", "animations/alice.json"))) {
                assertTrue(PackFileIO.readString(in).contains("animations"));
            }
            try (InputStream in = TlmPackZip.open(zip, id("maid", "textures/alice.png"))) {
                assertArrayEquals(texture, PackFileIO.readBytes(in));
            }
            try (InputStream in = TlmPackZip.open(zip, id("maid", "textures/variant.png"))) {
                assertArrayEquals(new byte[]{5}, PackFileIO.readBytes(in));
            }
            assertNull(TlmPackZip.open(zip, id("maid", "missing.png")));
        }
    }

    @Test
    void rejectsTraversalAndCapsDecompressedReads() throws IOException {
        Path path = temp.resolve("oversized.zip");
        try (ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            add(out, "assets/maid/maid_model.json", "{}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/../secret.json", "secret".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/models/large.json", "123456".getBytes(StandardCharsets.UTF_8));
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            assertNull(TlmPackZip.open(zip, id("maid", "../secret.json")));
            try (InputStream in = TlmPackZip.open(zip, id("maid", "models/large.json"))) {
                assertThrows(IOException.class, () -> PackFileIO.readBytes(in, 5));
            }
        }
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void add(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
