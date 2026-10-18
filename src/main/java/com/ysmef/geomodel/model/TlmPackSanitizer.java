package com.ysmef.geomodel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Copies only maid geometry, PNG textures and Gecko JSON animations from a remote pack. */
final class TlmPackSanitizer {
    private static final long MAX_FILE_BYTES = 128L * 1024 * 1024;

    private TlmPackSanitizer() {}

    static Map<String, byte[]> safeAssets(ZipFile zip, long maxExpandedBytes) throws IOException {
        return safeAssets(new AssetSource() {
            @Override public Set<String> namespaces() { return TlmPackZip.namespaces(zip); }
            @Override public InputStream open(ResourceLocation id) throws IOException { return TlmPackZip.open(zip, id); }
        }, maxExpandedBytes);
    }

    static Map<String, byte[]> safeAssets(Path source, long maxExpandedBytes) throws IOException {
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && TlmPackZip.isZipPack(source)) {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                Set<String> names = new HashSet<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!TlmPackArchive.safeEntryName(name) || !names.add(name)
                            || names.size() > TlmPackArchive.MAX_ENTRIES) {
                        throw new IOException("unsafe, duplicate or excessive ZIP entry: " + name);
                    }
                }
                return safeAssets(zip, maxExpandedBytes);
            }
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("TLM pack is neither a ZIP nor a directory");
        }
        Path root = source.toRealPath();
        Path assets = root.resolve("assets");
        return safeAssets(new AssetSource() {
            @Override public Set<String> namespaces() throws IOException {
                Set<String> result = new java.util.LinkedHashSet<>();
                if (!Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) return result;
                try (var dirs = Files.list(assets)) {
                    for (Path dir : (Iterable<Path>) dirs::iterator) {
                        String namespace = dir.getFileName().toString();
                        if (TlmPackZip.safeNamespace(namespace)
                                && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                                && PackFileIO.isInsideReal(dir, assets)) result.add(namespace);
                    }
                }
                return result;
            }
            @Override public InputStream open(ResourceLocation id) throws IOException {
                Path file = assets.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
                if (!file.startsWith(assets) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !PackFileIO.isInsideReal(file, assets)) return null;
                return Files.newInputStream(file);
            }
        }, maxExpandedBytes);
    }

    private interface AssetSource {
        Set<String> namespaces() throws IOException;
        InputStream open(ResourceLocation id) throws IOException;
    }

    private static Map<String, byte[]> safeAssets(AssetSource source, long maxExpandedBytes) throws IOException {
        Map<String, byte[]> assets = new TreeMap<>();
        long[] total = {0};
        for (String namespace : source.namespaces()) {
            ResourceLocation manifestId = ResourceLocation.fromNamespaceAndPath(namespace, "maid_model.json");
            byte[] manifestBytes = read(source, manifestId, total, maxExpandedBytes);
            if (manifestBytes == null) continue;
            JsonObject manifest;
            try {
                manifest = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("invalid TLM maid_model.json in " + namespace, e);
            }
            JsonArray models = manifest.getAsJsonArray("model_list");
            if (!manifest.has("pack_name") || !manifest.get("pack_name").isJsonPrimitive()
                    || models == null || models.isEmpty()) {
                throw new IOException("TLM pack has no usable pack_name or model_list in " + namespace);
            }
            for (JsonElement element : models) {
                if (!element.isJsonObject()) continue;
                JsonObject model = element.getAsJsonObject();
                if (!model.has("model_id")) throw new IOException("TLM model entry has no id");
                String id = model.get("model_id").getAsString();
                ResourceLocation modelId = ResourceLocation.tryParse(id);
                if (modelId == null || !TlmPackCatalog.validModelId(modelId.toString())) {
                    throw new IOException("unsafe TLM model id");
                }
                model.addProperty("model_id", modelId.toString());
                ResourceLocation geometry = model.has("model")
                        ? resource(model.get("model").getAsString(), ".json")
                        : ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(),
                                "models/entity/" + modelId.getPath() + ".json");
                copyJson(source, geometry, assets, total, maxExpandedBytes, true);
                ResourceLocation texture = model.has("texture")
                        ? resource(model.get("texture").getAsString(), ".png")
                        : ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(),
                                "textures/entity/" + modelId.getPath() + ".png");
                copy(source, texture, assets, total, maxExpandedBytes, false);
                if (model.has("extra_textures") && model.get("extra_textures").isJsonArray()) {
                    JsonArray filtered = new JsonArray();
                    for (JsonElement value : model.getAsJsonArray("extra_textures")) {
                        ResourceLocation extra = resource(value.getAsString(), ".png");
                        copy(source, extra, assets, total, maxExpandedBytes, false);
                        filtered.add(value);
                    }
                    model.add("extra_textures", filtered);
                }
                if (model.has("is_gecko") && model.get("is_gecko").getAsBoolean()
                        && model.has("animation") && model.get("animation").isJsonArray()) {
                    JsonArray filtered = new JsonArray();
                    for (JsonElement value : model.getAsJsonArray("animation")) {
                        ResourceLocation animation = resource(value.getAsString(), ".json");
                        copyJson(source, animation, assets, total, maxExpandedBytes, false);
                        filtered.add(value);
                    }
                    model.add("animation", filtered);
                } else {
                    // Non-Gecko TLM animation files are evaluated by Nashorn with Java access.
                    // A remote server must never make the client execute them.
                    model.remove("animation");
                }
            }
            manifest.remove("icon");
            assets.put(path(manifestId), manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (assets.isEmpty()) throw new IOException("no TLM maid models in server pack");
        return assets;
    }

    private static ResourceLocation resource(String raw, String suffix) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !id.getPath().endsWith(suffix)) throw new IOException("unsafe TLM resource reference");
        for (String segment : id.getPath().split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("unsafe TLM resource reference");
            }
        }
        return id;
    }

    private static void copyJson(AssetSource source, ResourceLocation id, Map<String, byte[]> assets,
                                 long[] total, long limit, boolean required) throws IOException {
        byte[] bytes = read(source, id, total, limit);
        if (bytes == null) {
            if (required) throw new IOException("missing TLM geometry " + id);
            return;
        }
        try { JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("invalid TLM JSON " + id, e); }
        assets.put(path(id), bytes);
    }

    private static void copy(AssetSource source, ResourceLocation id, Map<String, byte[]> assets,
                             long[] total, long limit, boolean required) throws IOException {
        byte[] bytes = read(source, id, total, limit);
        if (bytes == null) {
            if (required) throw new IOException("missing TLM resource " + id);
            return;
        }
        assets.put(path(id), bytes);
    }

    private static byte[] read(AssetSource source, ResourceLocation id, long[] total, long limit) throws IOException {
        try (InputStream in = source.open(id)) {
            if (in == null) return null;
            long remaining = limit - total[0];
            if (remaining < 1) throw new IOException("TLM pack expands too large");
            byte[] bytes = PackFileIO.readBytes(in, Math.min(MAX_FILE_BYTES, remaining));
            total[0] += bytes.length;
            return bytes;
        }
    }

    private static String path(ResourceLocation id) {
        return "assets/" + id.getNamespace() + "/" + id.getPath();
    }
}
