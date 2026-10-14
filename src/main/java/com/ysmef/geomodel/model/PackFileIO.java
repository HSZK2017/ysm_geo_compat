package com.ysmef.geomodel.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Bounded reads and real-path checks for model-pack files. */
public final class PackFileIO {
    private static final long MAX_FILE_BYTES = Math.max(1L,
            Math.min(Integer.MAX_VALUE - 8L,
                    Long.getLong("ysm_geo_compat.max_pack_file_bytes", 128L * 1024L * 1024L)));

    private PackFileIO() {}

    public static boolean isInsideReal(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    public static byte[] readBytes(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw tooLarge(file.toString(), MAX_FILE_BYTES);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readBytes(in);
        }
    }

    public static byte[] readBytes(InputStream in) throws IOException {
        return readBytes(in, MAX_FILE_BYTES);
    }

    static byte[] readBytes(InputStream in, long maxBytes) throws IOException {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("invalid model-pack byte limit: " + maxBytes);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw tooLarge("model-pack resource", maxBytes);
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    public static String readString(Path file) throws IOException {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    public static String readString(InputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    public interface TempFileWriter {
        void write(Path tempFile) throws IOException;
    }

    /** Write through a new temporary file, refusing linked output directories. */
    public static void writeAtomically(Path target, Path root, TempFileWriter writer) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path file = target.toAbsolutePath().normalize();
        Path parent = file.getParent();
        if (parent == null || !parent.startsWith(base)) {
            throw new IOException("generated file leaves the pack: " + target);
        }
        Files.createDirectories(base);
        Path current = base;
        for (Path segment : base.relativize(parent)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("linked or non-directory generated pack path: " + current);
            }
        }
        if (!isInsideReal(parent, base)) {
            throw new IOException("generated file leaves the pack: " + target);
        }
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            writer.write(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static IOException tooLarge(String source, long maxBytes) {
        return new IOException(source + " exceeds the " + maxBytes + " byte model-pack file limit");
    }
}
