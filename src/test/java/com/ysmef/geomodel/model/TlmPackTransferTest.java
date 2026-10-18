package com.ysmef.geomodel.model;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmPackTransferTest {
    @TempDir Path temp;

    @Test
    void folderPackBecomesDeterministicTlmReadableZipAndCatalogFindsVariants() throws IOException {
        Path packFolder = temp.resolve("tlm_custom_pack");
        Path pack = packFolder.resolve("maids");
        Path assets = pack.resolve("assets/my-maid");
        Files.createDirectories(assets.resolve("models/entity"));
        Files.createDirectories(assets.resolve("textures"));
        Files.writeString(assets.resolve("maid_model.json"), """
                {"pack_name":"Maid test pack","model_list":[{"model_id":"my-maid:alice","model":"my-maid:models/entity/alice.json",
                "extra_textures":["my-maid:textures/blue.png"]}]}
                """);
        Files.writeString(assets.resolve("models/entity/alice.json"), "{}");
        Files.write(assets.resolve("textures/blue.png"), new byte[]{1, 2, 3});

        assertEquals(pack, TlmPackCatalog.find(packFolder, "my-maid:alice"));
        String variant = "my-maid:alice_" + TlmPackCatalog.variantHash(
                ResourceLocation.fromNamespaceAndPath("my-maid", "textures/blue.png"));
        assertEquals(pack, TlmPackCatalog.find(packFolder, variant));
        assertNull(TlmPackCatalog.find(packFolder, "my-maid:bob"));

        byte[] first = TlmPackArchive.pack(pack, 1024 * 1024);
        assertArrayEquals(first, TlmPackArchive.pack(pack, 1024 * 1024));
        Path zip = temp.resolve("cached.zip");
        Files.write(zip, first);
        TlmPackArchive.validate(zip, 1024 * 1024, 1024 * 1024);
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            assertNotNull(archive.getEntry("assets/my-maid/"));
            assertEquals("my-maid:alice", TlmPackZip.modelList(archive, "my-maid")
                    .get(0).getAsJsonObject().get("model_id").getAsString());
        }
        assertEquals(TlmPackArchive.sha256(first), TlmPackArchive.sha256(zip, 1024 * 1024));
        Path unpacked = TlmPackArchive.materialize(zip, temp.resolve("cache"),
                TlmPackArchive.sha256(first), 1024 * 1024, 1024 * 1024);
        assertEquals(JsonParser.parseString(Files.readString(assets.resolve("maid_model.json"))),
                JsonParser.parseString(Files.readString(unpacked.resolve("assets/my-maid/maid_model.json"))));
    }

    @Test
    void zipPackIsOfferedByModelIdAndStoredWithoutChangingItsBytes() throws IOException {
        Path packFolder = temp.resolve("tlm_custom_pack");
        Files.createDirectories(packFolder);
        Path zip = packFolder.resolve("maids.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "assets/maid/", new byte[0]);
            add(out, "assets/maid/maid_model.json", "{\"pack_name\":\"Maid test pack\",\"model_list\":[{\"model_id\":\"maid:bob\"}]}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(zip, TlmPackCatalog.find(packFolder, "maid:bob"));
        byte[] original = Files.readAllBytes(zip);
        assertArrayEquals(original, TlmPackArchive.pack(zip, 1024 * 1024));
    }

    @Test
    void transferPayloadIgnoresLargeUnrelatedFilesForZipAndFolderPacks() throws IOException {
        Path folder = temp.resolve("source");
        Path assets = folder.resolve("assets/maid");
        Files.createDirectories(assets.resolve("models/entity"));
        Files.createDirectories(assets.resolve("sounds"));
        byte[] unrelated = new byte[64 * 1024];
        new Random(7).nextBytes(unrelated);
        Files.writeString(assets.resolve("maid_model.json"), """
                {"pack_name":"Maid","model_list":[{"model_id":"maid:alice",
                 "model":"maid:models/entity/alice.json"}]}
                """);
        Files.writeString(assets.resolve("models/entity/alice.json"), "{}");
        Files.write(assets.resolve("sounds/unrelated.ogg"), unrelated);

        long transferLimit = 8192;
        assertThrows(IOException.class, () -> TlmPackArchive.pack(folder, transferLimit));
        byte[] folderPayload = TlmPackArchive.packForTransfer(folder, transferLimit);
        assertTrue(folderPayload.length < transferLimit);
        Path sourceZip = temp.resolve("source.zip");
        Files.write(sourceZip, TlmPackArchive.pack(folder, 128 * 1024));
        assertThrows(IOException.class, () -> TlmPackArchive.pack(sourceZip, transferLimit));
        assertArrayEquals(folderPayload, TlmPackArchive.packForTransfer(sourceZip, transferLimit));

        Path received = temp.resolve("received.zip");
        Files.write(received, folderPayload);
        Path unpacked = TlmPackArchive.materialize(received, temp.resolve("unpacked"),
                TlmPackArchive.sha256(folderPayload), transferLimit, transferLimit);
        assertTrue(Files.exists(unpacked.resolve("assets/maid/models/entity/alice.json")));
        assertFalse(Files.exists(unpacked.resolve("assets/maid/sounds/unrelated.ogg")));
    }

    @Test
    void transferHashChangesWithMaidGeometryButNotUnrelatedAssets() throws IOException {
        Path folder = temp.resolve("source");
        Path assets = folder.resolve("assets/maid");
        Files.createDirectories(assets.resolve("models/entity"));
        Files.createDirectories(assets.resolve("sounds"));
        Files.writeString(assets.resolve("maid_model.json"), """
                {"pack_name":"Maid","model_list":[{"model_id":"maid:alice",
                 "model":"maid:models/entity/alice.json"}]}
                """);
        Path geometry = assets.resolve("models/entity/alice.json");
        Files.writeString(geometry, "{}");
        String initial = TlmPackArchive.sha256(TlmPackArchive.packForTransfer(folder, 8192));
        Files.writeString(assets.resolve("sounds/changed.ogg"), "ignored");
        assertEquals(initial, TlmPackArchive.sha256(TlmPackArchive.packForTransfer(folder, 8192)));
        Files.writeString(geometry, "{\"geometry\":true}");
        assertFalse(initial.equals(TlmPackArchive.sha256(TlmPackArchive.packForTransfer(folder, 8192))));
    }

    @Test
    void unqualifiedManifestModelIdUsesMinecraftNamespace() throws IOException {
        Path packFolder = temp.resolve("tlm_custom_pack");
        Files.createDirectories(packFolder);
        Path zip = packFolder.resolve("default-id.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "assets/maid/maid_model.json", """
                    {"pack_name":"Default namespace","model_list":[{"model_id":"alice"}]}
                    """.getBytes(StandardCharsets.UTF_8));
            add(out, "assets/minecraft/models/entity/alice.json", "{}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(zip, TlmPackCatalog.find(packFolder, "minecraft:alice"));
        Path unpacked = TlmPackArchive.materialize(zip, temp.resolve("cache"),
                TlmPackArchive.sha256(zip, 1024 * 1024), 1024 * 1024, 1024 * 1024);
        String manifest = Files.readString(unpacked.resolve("assets/maid/maid_model.json"));
        assertTrue(manifest.contains("minecraft:alice"));
        assertTrue(Files.exists(unpacked.resolve("assets/minecraft/models/entity/alice.json")));
    }

    @Test
    void receivedArchivesRejectTraversalAndExcessiveExpansion() throws IOException {
        Path traversal = temp.resolve("traversal.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(traversal))) {
            add(out, "assets/maid/maid_model.json", "{}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/../secret.json", "secret".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(IOException.class, () -> TlmPackArchive.validate(traversal, 1024 * 1024, 1024 * 1024));

        Path bomb = temp.resolve("compressed.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bomb))) {
            add(out, "assets/maid/maid_model.json", "{}".getBytes(StandardCharsets.UTF_8));
            byte[] large = new byte[8192];
            Arrays.fill(large, (byte) 'a');
            add(out, "assets/maid/models/large.json", large);
        }
        assertThrows(IOException.class, () -> TlmPackArchive.validate(bomb, 1024 * 1024, 4096));
        assertFalse(TlmPackArchive.safeEntryName("assets/../outside"));
    }

    @Test
    void remotePackCannotMakeTlmEvaluateJavascriptAnimations() throws IOException {
        Path zip = temp.resolve("remote.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "assets/maid/maid_model.json", """
                    {"pack_name":"Remote","model_list":[
                      {"model_id":"maid:safe","model":"maid:models/entity/safe.json",
                       "animation":["maid:animation/evil.js"]},
                      {"model_id":"maid:gecko","model":"maid:models/entity/gecko.json",
                       "is_gecko":true,"animation":["maid:animations/gecko.json"]}]}
                    """.getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/models/entity/safe.json", "{}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/models/entity/gecko.json", "{}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/animation/evil.js", "Java.type('java.lang.Runtime')".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/animations/gecko.json", "{\"animations\":{}}".getBytes(StandardCharsets.UTF_8));
            add(out, "assets/maid/chair_model.json", "{}".getBytes(StandardCharsets.UTF_8));
        }
        Path extracted = TlmPackArchive.materialize(zip, temp.resolve("cache"),
                TlmPackArchive.sha256(zip, 1024 * 1024),
                1024 * 1024, 1024 * 1024);
        assertFalse(Files.exists(extracted.resolve("assets/maid/animation/evil.js")));
        assertFalse(Files.exists(extracted.resolve("assets/maid/chair_model.json")));
        assertTrue(Files.exists(extracted.resolve("assets/maid/animations/gecko.json")));
        String manifest = Files.readString(extracted.resolve("assets/maid/maid_model.json"));
        assertFalse(manifest.contains("evil.js"));
        assertTrue(manifest.contains("animations/gecko.json"));
    }

    @Test
    void cachedArchiveMustMatchAdvertisedHashAndContainGeometry() throws IOException {
        Path zip = temp.resolve("incomplete.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "assets/maid/maid_model.json", """
                    {"pack_name":"Incomplete","model_list":[{"model_id":"maid:missing"}]}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        Path cache = temp.resolve("cache");
        assertThrows(IOException.class, () -> TlmPackArchive.materialize(zip, cache,
                "0".repeat(64), 1024 * 1024, 1024 * 1024));
        assertThrows(IOException.class, () -> TlmPackArchive.materialize(zip, cache,
                TlmPackArchive.sha256(zip, 1024 * 1024), 1024 * 1024, 1024 * 1024));
    }

    @Test
    void overlayIsRevokedAtDisconnect() {
        Path cached = temp.resolve("server/hash.zip");
        String hash = "a".repeat(64);
        try {
            assertTrue(TlmServerOverlay.activate(hash, cached));
            assertFalse(TlmServerOverlay.activate(hash, cached));
            assertEquals(cached.toAbsolutePath().normalize(), TlmServerOverlay.activePacks().get(0));
        } finally {
            assertTrue(TlmServerOverlay.clear());
        }
        assertTrue(TlmServerOverlay.activePacks().isEmpty());
    }

    @Test
    void obsoleteServerVersionIsRemovedAfterNoModelReferencesIt() {
        String oldHash = "a".repeat(64);
        String newHash = "b".repeat(64);
        try {
            assertTrue(TlmServerOverlay.activate(oldHash, temp.resolve("old")));
            assertTrue(TlmServerOverlay.activate(newHash, temp.resolve("new")));
            assertTrue(TlmServerOverlay.retainHashes(java.util.Set.of(newHash)));
            assertNull(TlmServerOverlay.activePack(oldHash));
            assertNotNull(TlmServerOverlay.activePack(newHash));
        } finally {
            TlmServerOverlay.clear();
        }
    }

    private static void add(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
