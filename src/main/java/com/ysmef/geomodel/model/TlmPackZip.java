package com.ysmef.geomodel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the same {@code assets/<namespace>/...} layout that TLM accepts in custom ZIP packs. */
final class TlmPackZip {
    private static final String MANIFEST = "maid_model.json";

    private TlmPackZip() {}

    static boolean isZipPack(Path path) {
        return path.getFileName().toString().endsWith(".zip");
    }

    static Set<String> namespaces(ZipFile zip) {
        Set<String> result = new LinkedHashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith("assets/") || !name.endsWith("/" + MANIFEST)) {
                continue;
            }
            String namespace = name.substring("assets/".length(), name.length() - MANIFEST.length() - 1);
            if (safeNamespace(namespace)) {
                result.add(namespace);
            }
        }
        return result;
    }

    static JsonArray modelList(ZipFile zip, String namespace) throws IOException {
        if (!safeNamespace(namespace)) {
            return null;
        }
        try (InputStream in = open(zip, ResourceLocation.fromNamespaceAndPath(namespace, MANIFEST))) {
            return in == null ? null : JsonParser.parseString(PackFileIO.readString(in))
                    .getAsJsonObject().getAsJsonArray("model_list");
        }
    }

    static InputStream open(ZipFile zip, ResourceLocation location) throws IOException {
        if (location == null || !safeNamespace(location.getNamespace()) || !safePath(location.getPath())) {
            return null;
        }
        ZipEntry entry = zip.getEntry("assets/" + location.getNamespace() + "/" + location.getPath());
        return entry == null || entry.isDirectory() ? null : zip.getInputStream(entry);
    }

    static boolean safeNamespace(String namespace) {
        if (namespace.isEmpty() || namespace.equals(".") || namespace.equals("..")) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean safePath(String path) {
        if (path.isEmpty() || path.startsWith("/")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
