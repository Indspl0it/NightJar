package dev.nightjar.payload.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The last explicit firewall switch from the app, kept in the payload's device-protected directory.
 *
 * <p>The file holds one character: {@code 1} (on) or {@code 0} (off). It is in device-protected storage, so the
 * payload can read it after a reboot before the user unlocks the phone. A missing or bad file means "no switch
 * received" and the firewall stays on.
 *
 * <p>Thread: the payload worker only (file I/O). Never on the main looper, a HAL thread or a binder thread.
 */
public final class FirewallFlagFile {
    static final String NAME = "firewall_enabled";
    private static final String TMP_SUFFIX = ".tmp";

    private final File file;

    public FirewallFlagFile(File dir) {
        this.file = new File(dir, NAME);
    }

    /** The stored switch, or {@code null} when the file is missing, empty, bad or unreadable. Never throws. */
    public Boolean read() {
        try {
            if (!file.isFile()) return null;
            byte[] buf = new byte[4];
            int n;
            try (FileInputStream in = new FileInputStream(file)) {
                n = in.read(buf);
            }
            if (n != 1) return null;
            if (buf[0] == '1') return Boolean.TRUE;
            if (buf[0] == '0') return Boolean.FALSE;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Writes the switch: first to a temporary file, then a move that replaces the old file, so a crash during the
     * write keeps the old value.
     *
     * @return true when the new value is stored. Never throws.
     */
    public boolean write(boolean enabled) {
        File tmp = new File(file.getPath() + TMP_SUFFIX);
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(enabled ? '1' : '0');
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
            // Fail open: the stored value does not change.
        }
    }
}
