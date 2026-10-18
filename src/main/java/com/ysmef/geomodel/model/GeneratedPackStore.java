package com.ysmef.geomodel.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.geomodel.YSMGeoCompat;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Owns generated resource-pack paths, safe file access, and texture mirroring. */
final class GeneratedPackStore {

    private GeneratedPackStore() {}

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_geo_compat");
    private static final Path PACK_ROOT = CONFIG_ROOT.resolve("resourcepack");
    private static final Path MESH_DIR = PACK_ROOT.resolve("assets").resolve(YSMGeoCompat.MODID).resolve("animmodels").resolve("entity");
    private static final Path RUNTIME_DIR = PACK_ROOT.resolve("assets").resolve(YSMGeoCompat.MODID).resolve("ysm_runtime").resolve("entity");
    private static final Path TEXTURE_DIR = PACK_ROOT.resolve("assets").resolve(YSMGeoCompat.MODID).resolve("textures");
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");

    static final String MESH_NAMESPACE = YSMGeoCompat.MODID;

    /**
     * relative texture path -> content hash of the file already mirrored into the
     * generated pack, so a reload that re-registers unchanged textures does not
     * rewrite them (see writePackTexture).
     */
    private static final Map<String, Integer> PACK_TEXTURE_HASHES = new ConcurrentHashMap<>();

    /**
     * The resource pack root that should be registered as a client resource pack.
     */
    static Path getPackRoot() {
        return PACK_ROOT;
    }

    /**
     * Directory inside the generated pack where Epic Fight animmodels mesh JSONs
     * live (assets/<modid>/animmodels/entity).
     */
    static Path getMeshDir() {
        return MESH_DIR;
    }

    /**
     * The runtime script JSON (bone table + molang animations) generated for the
     * given mesh id, evaluated by YSMRuntimeModel at render time.
     */
    static Path getRuntimeFile(String meshId) {
        // sanitize() guarantees a traversal-free relative path, so this resolve
        // cannot escape RUNTIME_DIR; resolveInside re-checks it as a fence in
        // front of the filesystem (the fallback is unreachable for sanitize
        // output and exists only to keep the method total).
        String name = sanitize(meshId);
        Path resolved = resolveInside(RUNTIME_DIR, name + ".json");
        return resolved != null ? resolved : RUNTIME_DIR.resolve(name + ".json");
    }

    /** modelId -> meshId (sanitized), for runtime lookup. */
    static String meshIdOf(String modelId) {
        return sanitize(modelId);
    }

    /**
     * The mesh file name used for TLM model-pack meshes: "namespace__path"
     * (mirrors the naming in TlmModelLibrary). Both halves go through the same
     * {@link #sanitize} the writer uses, so the lookup can never drift from the
     * generated file name.
     */
    static String tlmMeshIdOf(String modelId) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(modelId);
        if (rl == null) {
            return null;
        }
        return sanitize(rl.getNamespace()) + "__" + sanitize(rl.getPath());
    }

    /**
     * Delete generated pack textures that no registered model refers to any more.
     *
     * Called once per mesh generation, after every model was converted and its
     * textures registered. Without this the pack accumulates the textures of
     * models that were removed from a pack, renamed, or registered under an
     * earlier naming scheme - leftovers that are never read again but stay in the
     * user's config folder forever.
     *
     * Does nothing when nothing was registered, so a failed generation can never
     * wipe the folder.
     */
    static void deleteStalePackTextures(Set<String> keep) {
        if (keep.isEmpty()) {
            return;
        }
        try (var stream = Files.walk(TEXTURE_DIR)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = TEXTURE_DIR.relativize(path).toString().replace('\\', '/');
                if (!keep.contains("textures/" + relative)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // a file locked by another process stays; the next reload retries
                    }
                }
            });
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to clean the generated pack textures: {}",
                    e.toString());
        }
    }

    /**
     * Ensure the generated resource pack skeleton exists (called before the pack
     * repository is built).
     */
    static void preparePackFolder() {
        try {
            Files.createDirectories(MESH_DIR);
            Files.createDirectories(RUNTIME_DIR);
            Files.createDirectories(TEXTURE_DIR);
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

    /**
     * Rewrite an untrusted, model-supplied id into a relative path that cannot
     * escape the directory it is resolved against.
     *
     * Model packs are third-party files. The previous version appended '.' and
     * '/' verbatim, so a manifest declaring {@code "model_id": "x:../../../../evil"}
     * produced an id that {@code Path.resolve} walked straight out of the
     * generated pack - an arbitrary file read (model/texture/animation loads)
     * and arbitrary write (generated mesh/runtime JSON) primitive.
     *
     * The rewrite therefore does three things:
     * - folds characters outside the resource-location alphabet to '_' and
     *   appends a hash suffix when anything was folded, so distinct ids stay
     *   distinct (failures can never silently collide);
     * - drops empty segments, which removes the leading/doubled-slash
     *   (absolute-path) form;
     * - replaces the traversal segments ".." and "." with "_".
     *
     * The result only ever contains {@code [a-z0-9/._-]} with non-empty,
     * non-traversal segments, i.e. it is safe to resolve against any root.
     */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean rewritten = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
                rewritten = true;
            }
        }
        String[] segments = sb.toString().split("/");
        sb.setLength(0);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals("..") || segment.equals(".")) {
                segment = "_";
                rewritten = true;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segment);
        }
        if (rewritten) {
            sb.append('_').append(Integer.toHexString(value.hashCode()));
        }
        return sb.toString();
    }

    /**
     * Resolve a model-supplied relative path against {@code root}, returning
     * null unless the result stays inside {@code root}.
     *
     * Rejects absolute paths, drive-letter and UNC forms, backslashes, NUL and
     * the "." / ".." segments, then re-checks the normalized result against the
     * normalized root. This is the lexical fence that every filesystem access
     * driven by a model pack must pass through; a symlink-based escape still
     * needs a {@code toRealPath} check, but no model pack can reach one without
     * passing this first.
     */
    static Path resolveInside(Path root, String relative) {
        if (root == null || relative == null || relative.isEmpty()) {
            return null;
        }
        if (relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0 || relative.startsWith("/")) {
            return null;
        }
        if (relative.length() >= 2 && relative.charAt(1) == ':') {
            return null;
        }
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    /**
     * Write the texture into the generated pack so the resource location we hand
     * out actually resolves through the resource manager.
     *
     * Two consumers need this. Anything that loads a texture by resource
     * location - vanilla model layers, Epic Fight's own texture handling, other
     * mods - only finds it through the ResourceManager, and a DynamicTexture
     * registered with the texture manager is invisible to that path (it throws
     * FileNotFoundException, rendering the mesh with red/missing edges). And a
     * pack on disk is the only way a user can inspect or replace the texture of
     * a converted model.
     *
     * PNG/JPEG payloads are copied verbatim (the decoder sniffs the format by
     * magic bytes); WebP/AVIF payloads go through YSM's ImageStream decoders and
     * raw-RGBA payloads through the texture decoder, both re-encoded to PNG -
     * writing the encoded bytes of those formats verbatim would produce a file
     * whose extension lies about its content.
     *
     * Runs off the render thread (registration happens during conversion), so
     * the write never stalls a frame.
     */
    static void writePackTexture(String safeRelativePath, byte[] data) {
        if (safeRelativePath.isEmpty() || data == null || data.length == 0) {
            return;
        }
        int hash = Arrays.hashCode(data);
        Integer previous = PACK_TEXTURE_HASHES.get(safeRelativePath);
        if (previous != null && previous == hash) {
            // the same bytes were already written in this session; conversion is
            // re-run on every config/pack reload, and rewriting 235 identical
            // files each time would be pure disk churn
            return;
        }
        Path file = resolveInside(TEXTURE_DIR, safeRelativePath + ".png");
        if (file == null) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: refused to write pack texture outside the pack root: {}",
                    safeRelativePath);
            return;
        }
        try {
            if (YSMTextureDecoder.isPng(data) || YSMTextureDecoder.isJpeg(data)) {
                PackFileIO.writeAtomically(file, PACK_ROOT, tmp -> Files.write(tmp, data));
            } else {
                NativeImage image = YSMTextureDecoder.decodeTexture(safeRelativePath, data);
                if (image == null) {
                    return;
                }
                try {
                    PackFileIO.writeAtomically(file, PACK_ROOT, image::writeToFile);
                } finally {
                    image.close();
                }
            }
            PACK_TEXTURE_HASHES.put(safeRelativePath, hash);
        } catch (Throwable t) {
            // A failed mirror is not fatal: ensureTextureUploaded still feeds the
            // DynamicTexture from TEXTURE_DATA, so the model renders textured.
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to write pack texture {}: {}",
                    safeRelativePath, t.toString());
        }
    }

    /**
     * Re-read a texture's bytes from the generated pack file.
     *
     * Registration mirrors every texture into the pack (see
     * {@link #writePackTexture}), so the file is the durable copy of a texture
     * whose in-memory bytes were dropped after upload or released by an eviction.
     * Returns null when the pack has no such file (an unsupported format that
     * could not be re-encoded, or a deleted pack), which leaves the caller - and
     * the renderer - on the fallback path.
     *
     * Runs on the decode pool: no caller is on the render thread.
     */
    static byte[] readPackTextureBytes(ResourceLocation rl) {
        if (rl == null || !MESH_NAMESPACE.equals(rl.getNamespace())) {
            return null;
        }
        try {
            Path file = resolveInside(PACK_ROOT.resolve("assets").resolve(MESH_NAMESPACE), rl.getPath());
            if (file == null || !Files.isRegularFile(file)
                    || !PackFileIO.isInsideReal(file, PACK_ROOT)) {
                return null;
            }
            return PackFileIO.readBytes(file);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to re-read texture {} from the pack: {}",
                    rl, t.toString());
            return null;
        }
    }
}
