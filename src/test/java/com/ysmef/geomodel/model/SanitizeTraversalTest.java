package com.ysmef.geomodel.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the path-traversal defense of the model-pack read/write paths.
 *
 * Model packs are third-party files: every manifest-declared model / texture /
 * animation path and every model id is untrusted input that used to be resolved
 * straight against the generated pack root, which allowed escaping it (arbitrary
 * file read, and arbitrary file write of the generated mesh/runtime JSON).
 */
class SanitizeTraversalTest {

    private static final Path ROOT = Paths.get("config", "ysm_geo_compat", "resourcepack",
            "assets", "ysm_geo_compat", "ysm_runtime", "entity");

    @Test
    void legitimateIdsAreUnchanged() {
        for (String id : new String[]{
                "tlm/winefox_blue",
                "tlm/touhou_little_maid/winefox/red",
                "ns__models/entity/winefox",
                "winefox_blue_0123456789abcdef0123456789abcdef",
        }) {
            assertEquals(id, YSMMeshLibrary.sanitize(id), "a legitimate id must not be rewritten: " + id);
        }
    }

    @Test
    void traversalSegmentsAreNeutralized() {
        for (String hostile : new String[]{
                "../../../../../../../../evil",
                "..",
                ".",
                "a/../../b",
                "models/./../../x",
                "..\\..\\evil",
                "/etc/passwd",
        }) {
            String out = YSMMeshLibrary.sanitize(hostile);
            for (String segment : out.split("/")) {
                assertFalse(segment.equals("..") || segment.equals("."),
                        "sanitize left a traversal segment for '" + hostile + "': " + out);
            }
            assertFalse(out.startsWith("/"), "sanitize left an absolute form for '" + hostile + "': " + out);
            assertNotNull(YSMMeshLibrary.resolveInside(ROOT, out + ".json"),
                    "sanitized output must be resolvable: '" + hostile + "' -> '" + out + "'");
        }
    }

    @Test
    void resolveInsideRejectsHostileInput() {
        String[] rejected = {
                "../../../../etc/passwd",
                "/etc/passwd",
                "a/../../b",
                "a/./b",
                "C:/Windows/System32/x.json",
                "c:/x",
                "\\\\server\\share\\x",
                "a\\..\\b",
                "a//b",
                "a/\0b",
                "",
        };
        for (String r : rejected) {
            assertNull(YSMMeshLibrary.resolveInside(ROOT, r), "resolveInside accepted hostile input: " + r);
        }
    }

    @Test
    void resolveInsideAcceptsLegitimatePathsAndStaysInside() {
        Path base = ROOT.toAbsolutePath().normalize();
        for (String ok : new String[]{
                "tlm/winefox/red.json",
                "winefox_blue.json",
                "tlm/ns__models/entity/winefox.json",
        }) {
            Path resolved = YSMMeshLibrary.resolveInside(ROOT, ok);
            assertNotNull(resolved, "resolveInside rejected a legitimate path: " + ok);
            assertTrue(resolved.toAbsolutePath().normalize().startsWith(base),
                    "an accepted path escaped the root: " + resolved);
        }
    }

    /**
     * The name the writer derives and the name the runtime lookup derives must be
     * byte-identical, otherwise a model's runtime script is never found and the
     * bind-armature pose correction silently disables itself.
     */
    @Test
    void writerAndLookupDeriveTheSameName() {
        // halves that are valid resource-location paths (the only input the lookup
        // ever sees, since it parses a ResourceLocation first)
        for (String half : new String[]{"touhou_little_maid/winefox_blue", "../evil", "models/./../x", "a.weird-name_1"}) {
            String writerName = YSMMeshLibrary.sanitize("tlm") + "__" + YSMMeshLibrary.sanitize(half);
            String lookupName = YSMMeshLibrary.tlmMeshIdOf("tlm:" + half);
            assertEquals(writerName, lookupName, "writer/lookup name drift for '" + half + "'");

            Path writerPath = YSMMeshLibrary.resolveInside(ROOT, "tlm/" + writerName + ".json");
            Path lookupPath = YSMMeshLibrary.resolveInside(ROOT, "tlm/" + lookupName + ".json");
            assertNotNull(writerPath);
            assertEquals(writerPath, lookupPath);
        }
    }

    @Test
    void lookupRejectsNonResourceLocationInput() {
        // a space is outside the resource-location alphabet, so there is no id to
        // derive at all (the writer never produces one either)
        assertNull(YSMMeshLibrary.tlmMeshIdOf("tlm:a b c"));
    }

    @Test
    void endToEndHostileModelIdCannotEscape() {
        Path packRoot = Paths.get("config", "ysm_geo_compat", "resourcepack");
        Path packAssets = packRoot.resolve("assets");
        String modelId = "evil:../../../../../../../../pwned";

        String namespace = modelId.substring(0, modelId.indexOf(':'));
        String path = modelId.substring(modelId.indexOf(':') + 1);

        // the generated mesh/runtime file name
        String generated = YSMMeshLibrary.sanitize(namespace) + "__" + YSMMeshLibrary.sanitize(path) + ".json";
        Path written = YSMMeshLibrary.resolveInside(
                packAssets.resolve("ysm_geo_compat").resolve("animmodels").resolve("entity").resolve("tlm"), generated);
        assertNotNull(written, "a hostile id must still produce a resolvable (inside-root) write path");
        assertTrue(written.toAbsolutePath().normalize().startsWith(packRoot.toAbsolutePath().normalize()),
                "hostile write escaped the pack: " + written);

        // the read side
        assertNull(YSMMeshLibrary.resolveInside(packAssets, namespace + "/" + path),
                "hostile read must be refused");
    }

    @Test
    void tlmMeshIdOfRejectsUnparseableIds() {
        assertNull(YSMMeshLibrary.tlmMeshIdOf("not a resource location"));
    }
}
