package com.ysmef.geomodel.model;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Bounded ZIP representation of a TLM folder or ZIP pack, shared by both peers. */
public final class TlmPackArchive {
    public static final int MAX_ENTRIES = 4096;

    private TlmPackArchive() {}

    /** Canonical maid-only payload; unrelated sound/chair files do not consume the transfer limit. */
    public static byte[] packForTransfer(Path source, long maxBytes) throws IOException {
        Map<String, byte[]> assets = TlmPackSanitizer.safeAssets(source, expandedLimit(maxBytes));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new LimitedOutputStream(bytes, maxBytes))) {
            for (var asset : assets.entrySet()) {
                ZipEntry entry = new ZipEntry(asset.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(asset.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public static byte[] pack(Path source, long maxBytes) throws IOException {
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && TlmPackZip.isZipPack(source)) {
            if (Files.size(source) > maxBytes) throw new IOException("ZIP pack is too large");
            validate(source, maxBytes, expandedLimit(maxBytes));
            try (InputStream in = Files.newInputStream(source)) {
                return PackFileIO.readBytes(in, maxBytes);
            }
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("TLM pack is neither a ZIP nor a directory");
        }
        Path root = source.toRealPath();
        List<Path> entries = new ArrayList<>();
        long total = 0;
        try (var walk = Files.walk(source)) {
            for (Path entry : (Iterable<Path>) walk::iterator) {
                if (entry.equals(source)) continue;
                if (Files.isSymbolicLink(entry) || !entry.toRealPath().startsWith(root)) {
                    throw new IOException("linked path in TLM pack: " + entry);
                }
                String relative = relative(source, entry);
                if (!safeEntryName(relative)) throw new IOException("unsafe TLM pack entry: " + relative);
                entries.add(entry);
                if (entries.size() > MAX_ENTRIES) throw new IOException("too many TLM pack entries");
                if (Files.isRegularFile(entry)) {
                    total += Files.size(entry);
                    if (total > expandedLimit(maxBytes)) throw new IOException("TLM pack expands too large");
                }
            }
        }
        entries.sort(Comparator.comparing(entry -> relative(source, entry)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new LimitedOutputStream(bytes, maxBytes))) {
            byte[] buffer = new byte[65536];
            for (Path entry : entries) {
                boolean directory = Files.isDirectory(entry);
                String name = relative(source, entry) + (directory ? "/" : "");
                ZipEntry zipped = new ZipEntry(name);
                zipped.setTime(0L);
                zip.putNextEntry(zipped);
                if (!directory) {
                    try (InputStream in = Files.newInputStream(entry)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public static void validate(Path zipPath, long maxBytes, long maxExpandedBytes) throws IOException {
        if (Files.size(zipPath) > maxBytes) throw new IOException("ZIP pack is too large");
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Set<String> seen = new HashSet<>();
            int count = 0;
            long expanded = 0;
            byte[] buffer = new byte[65536];
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!safeEntryName(name) || !seen.add(name) || ++count > MAX_ENTRIES) {
                    throw new IOException("unsafe, duplicate or excessive ZIP entry: " + name);
                }
                if (entry.isDirectory()) continue;
                CRC32 crc = new CRC32();
                try (InputStream in = zip.getInputStream(entry)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        expanded += n;
                        if (expanded > maxExpandedBytes) throw new IOException("ZIP pack expands too large");
                        crc.update(buffer, 0, n);
                    }
                }
                if (entry.getCrc() != crc.getValue()) throw new IOException("ZIP entry CRC mismatch: " + name);
            }
            if (TlmPackZip.namespaces(zip).isEmpty()) throw new IOException("ZIP has no TLM maid_model.json");
        }
    }

    /** Safely extract a verified archive into a hash-named cache folder for TLM's folder loader. */
    public static Path materialize(Path zipPath, Path cacheRoot, String hash,
                                   long maxBytes, long maxExpandedBytes) throws IOException {
        if (!validSha256(hash)) throw new IOException("invalid cache hash");
        if (!sha256(zipPath, maxBytes).equals(hash)) throw new IOException("TLM pack SHA-256 mismatch");
        validate(zipPath, maxBytes, maxExpandedBytes);
        Path root = cacheRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(hash);
        Path staging = Files.createTempDirectory(root, ".staging-");
        try {
            long expanded = 0;
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                for (var asset : TlmPackSanitizer.safeAssets(zip, maxExpandedBytes).entrySet()) {
                    String name = asset.getKey();
                    if (!safeEntryName(name)) throw new IOException("unsafe TLM asset");
                    Path output = staging.resolve(name).normalize();
                    if (!output.startsWith(staging) || output.equals(staging)) throw new IOException("ZIP entry escapes cache");
                    expanded += asset.getValue().length;
                    if (expanded > maxExpandedBytes) throw new IOException("TLM assets expand too large");
                    Files.createDirectories(output.getParent());
                    Files.write(output, asset.getValue(), java.nio.file.StandardOpenOption.CREATE_NEW);
                }
            }
            deleteTree(target, root);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, target);
            }
            return target;
        } finally {
            deleteTree(staging, root);
        }
    }

    private static void deleteTree(Path target, Path root) throws IOException {
        Path path = target.toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("cache cleanup escapes root");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    public static String sha256(Path file, long maxBytes) throws IOException {
        if (Files.size(file) > maxBytes) throw new IOException("TLM pack is too large");
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("TLM pack is too large");
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /** Cheap invalidation key for the server's cached offer; content is still rechecked before sending. */
    public static long signature(Path source) throws IOException {
        long value = 0xcbf29ce484222325L;
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return mix(value, source.getFileName() + ":" + Files.size(source) + ":"
                    + Files.getLastModifiedTime(source).toMillis());
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing TLM pack");
        List<Path> entries;
        try (var walk = Files.walk(source)) { entries = walk.sorted().toList(); }
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) throw new IOException("linked TLM pack entry");
            value = mix(value, relative(source, entry) + ":"
                    + (Files.isRegularFile(entry) ? Files.size(entry) : 0) + ":"
                    + Files.getLastModifiedTime(entry).toMillis());
        }
        return value;
    }

    private static long mix(long value, String text) {
        for (int i = 0; i < text.length(); i++) {
            value ^= text.charAt(i);
            value *= 0x100000001b3L;
        }
        return value;
    }

    public static boolean validSha256(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is required", e); }
    }

    public static long expandedLimit(long maxBytes) {
        return Math.min(512L * 1024 * 1024, Math.max(maxBytes, maxBytes * 4));
    }

    private static String relative(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }

    static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) return false;
        String normalized = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long maximum;
        private long written;

        LimitedOutputStream(OutputStream out, long maximum) {
            super(out);
            this.maximum = maximum;
        }

        @Override public void write(int value) throws IOException {
            if (++written > maximum) throw new IOException("TLM ZIP pack exceeds transfer limit");
            out.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > maximum - written) throw new IOException("TLM ZIP pack exceeds transfer limit");
            out.write(bytes, offset, length);
            written += length;
        }
    }
}
