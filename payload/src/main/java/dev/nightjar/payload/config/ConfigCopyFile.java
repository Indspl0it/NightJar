package dev.nightjar.payload.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * One configuration document from the app ({@code rules.json} or {@code settings.json}), kept in the payload's
 * device-protected directory.
 *
 * <p>The payload reads the copy after a reboot before the user unlocks the phone, when the app cannot read its own
 * credential-protected storage. A missing, empty, oversized or non-UTF-8 file means "no copy", and the payload keeps
 * its defaults (everything ALLOW).
 *
 * <p>Thread: the payload worker only (file I/O). Never on the main looper, a HAL thread or a binder thread.
 */
public final class ConfigCopyFile {
    static final String RULES = "rules.json";
    static final String SETTINGS = "settings.json";
    /** Upper bound for one stored document; a larger file is not read and a larger document is not written. */
    static final int MAX_BYTES = 1024 * 1024;
    private static final String TMP_SUFFIX = ".tmp";

    private final File file;

    public ConfigCopyFile(File dir, String name) {
        this.file = new File(dir, name);
    }

    /** The stored document, or {@code null} when there is no usable copy. Never throws. */
    public String read() {
        try {
            if (!file.isFile()) return null;
            long size = file.length();
            if (size <= 0 || size > MAX_BYTES) return null;
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length == 0 || bytes.length > MAX_BYTES) return null;
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (Throwable t) {
            // Includes a CharacterCodingException for a file that is not UTF-8.
            return null;
        }
    }

    /**
     * Writes the document: first to a temporary file, then a move that replaces the old file, so a crash during the
     * write keeps the old copy.
     *
     * @return true when the new document is stored. Never throws.
     */
    public boolean write(String document) {
        if (document == null || document.isEmpty()) return false;
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) return false;
        File tmp = new File(file.getPath() + TMP_SUFFIX);
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            deleteQuietly(tmp);
            return false;
        }
    }

    private static void deleteQuietly(File f) {
        try {
            // A temporary file that stays is replaced by the next write.
            f.delete();
        } catch (Throwable ignored) {
            // Fail open: the stored copy does not change.
        }
    }
}
