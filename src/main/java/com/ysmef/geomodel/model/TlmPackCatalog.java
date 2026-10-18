package com.ysmef.geomodel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Finds the TLM custom pack that declares a synced maid model id. */
public final class TlmPackCatalog {
    private TlmPackCatalog() {}

    public static Path find(Path packFolder, String modelId) throws IOException {
        if (!validModelId(modelId) || !Files.isDirectory(packFolder)) return null;
        Path found = null;
        try (var stream = Files.list(packFolder)) {
            for (Path pack : (Iterable<Path>) stream::iterator) {
                if (!PackFileIO.isInsideReal(pack, packFolder)) continue;
                try {
                    if (declares(pack, modelId)) found = pack;
                } catch (Exception ignored) {
                    // A damaged pack does not hide models from other packs.
                }
            }
        }
        return found;
    }

    private static boolean declares(Path pack, String modelId) throws IOException {
        if (Files.isRegularFile(pack) && TlmPackZip.isZipPack(pack)) {
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                for (String namespace : TlmPackZip.namespaces(zip)) {
                    if (contains(TlmPackZip.modelList(zip, namespace), modelId)) return true;
                }
            }
        } else if (Files.isDirectory(pack)) {
            Path assets = pack.resolve("assets");
            if (!Files.isDirectory(assets) || !PackFileIO.isInsideReal(assets, pack)) return false;
            try (var namespaces = Files.list(assets)) {
                for (Path namespace : (Iterable<Path>) namespaces::iterator) {
                    if (!Files.isDirectory(namespace) || !PackFileIO.isInsideReal(namespace, assets)) continue;
                    Path manifest = namespace.resolve("maid_model.json");
                    if (!Files.isRegularFile(manifest) || !PackFileIO.isInsideReal(manifest, assets)) continue;
                    JsonArray models = JsonParser.parseString(PackFileIO.readString(manifest))
                            .getAsJsonObject().getAsJsonArray("model_list");
                    if (contains(models, modelId)) return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(JsonArray models, String requested) {
        if (models == null) return false;
        for (JsonElement value : models) {
            if (!value.isJsonObject()) continue;
            var entry = value.getAsJsonObject();
            if (!entry.has("model_id")) continue;
            ResourceLocation baseId = ResourceLocation.tryParse(entry.get("model_id").getAsString());
            if (baseId == null || !validModelId(baseId.toString())) continue;
            String base = baseId.toString();
            if (requested.equals(base)) return true;
            if (!entry.has("extra_textures") || !entry.get("extra_textures").isJsonArray()) continue;
            for (JsonElement texture : entry.getAsJsonArray("extra_textures")) {
                ResourceLocation id = ResourceLocation.tryParse(texture.getAsString());
                if (id != null && requested.equals(base + "_" + variantHash(id))) return true;
            }
        }
        return false;
    }

    public static boolean validModelId(String modelId) {
        if (modelId == null || modelId.length() > 256 || modelId.isEmpty()) return false;
        ResourceLocation id = ResourceLocation.tryParse(modelId);
        if (id == null || !modelId.equals(id.toString())) return false;
        for (String segment : id.getPath().split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /** The suffix used by TLM's CustomModelPack#decorate for extra textures. */
    static String variantHash(ResourceLocation texture) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(texture.getPath().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by TLM variant ids", e);
        }
    }
}
