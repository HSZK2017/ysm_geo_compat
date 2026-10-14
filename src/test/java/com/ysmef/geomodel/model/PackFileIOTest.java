package com.ysmef.geomodel.model;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PackFileIOTest {
    @TempDir Path temp;

    @Test
    void explicitModelNamespaceSelectsThatNamespace() throws IOException {
        Path pack = temp.resolve("pack");
        Path shared = pack.resolve("assets/shared/models/entity/maid.json");
        Files.createDirectories(shared.getParent());
        Files.writeString(shared, "{}", StandardCharsets.UTF_8);

        Path resolved = TlmModelLibrary.resolveInPackAssets(pack,
                ResourceLocation.fromNamespaceAndPath("shared", "models/entity/maid.json"));
        assertEquals(shared.toAbsolutePath(), resolved);
    }

    @Test
    void symlinkCannotReadOutsidePack() throws IOException {
        Path pack = temp.resolve("pack");
        Path ns = pack.resolve("assets/maid/models/entity");
        Files.createDirectories(ns);
        Path outside = temp.resolve("outside.json");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(ns.resolve("linked.json"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }

        assertNull(TlmModelLibrary.resolveInPackAssets(pack,
                ResourceLocation.fromNamespaceAndPath("maid", "models/entity/linked.json")));
    }

    @Test
    void generatedOutputRejectsLinkedDirectory() throws IOException {
        Path root = temp.resolve("generated");
        Path outside = temp.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root.resolve("animmodels"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }

        assertThrows(IOException.class, () -> PackFileIO.writeAtomically(
                root.resolve("animmodels/entity/maid.json"), root,
                tmp -> Files.writeString(tmp, "mesh", StandardCharsets.UTF_8)));
        assertTrue(Files.notExists(outside.resolve("entity/maid.json")));
    }

    @Test
    void generatedOutputCreatesDirectoriesAndReplacesFile() throws IOException {
        Path root = temp.resolve("generated");
        Path target = root.resolve("animmodels/entity/maid.json");
        PackFileIO.writeAtomically(target, root,
                tmp -> Files.writeString(tmp, "first", StandardCharsets.UTF_8));
        PackFileIO.writeAtomically(target, root,
                tmp -> Files.writeString(tmp, "second", StandardCharsets.UTF_8));
        assertEquals("second", Files.readString(target));
    }

    @Test
    void streamIsCappedEvenWhenItsSizeIsUnknown() throws IOException {
        byte[] payload = "123456".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload, PackFileIO.readBytes(new ByteArrayInputStream(payload), 6));
        IOException error = assertThrows(IOException.class,
                () -> PackFileIO.readBytes(new ByteArrayInputStream(payload), 5));
        assertTrue(error.getMessage().contains("5 byte"));
    }
}
