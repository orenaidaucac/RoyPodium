package com.github.orenaidaucac.royPodium.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes files safely via a temporary file and atomic move to prevent corruption on crash.
 */
public final class AtomicFileUtil {

    private AtomicFileUtil() {
    }

    /**
     * Writes UTF-8 content to {@code target} atomically.
     */
    public static void writeAtomic(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = Files.createTempFile(
                parent != null ? parent : Path.of("."),
                target.getFileName().toString(),
                ".tmp"
        );
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
