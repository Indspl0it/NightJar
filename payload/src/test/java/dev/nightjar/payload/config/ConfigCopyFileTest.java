package dev.nightjar.payload.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A stored document survives a restart, and a missing or bad file means "no copy". */
class ConfigCopyFileTest {
    @TempDir
    File dir;

    @Test
    void aMissingFileReadsAsNoCopy() {
        assertNull(new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
    }

    @Test
    void aWrittenDocumentIsReadByANewInstance() {
        assertTrue(new ConfigCopyFile(dir, ConfigCopyFile.RULES).write("{\"revision\":1}"));
        assertEquals("{\"revision\":1}", new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
        assertTrue(new ConfigCopyFile(dir, ConfigCopyFile.RULES).write("{\"revision\":2}"));
        assertEquals("{\"revision\":2}", new ConfigCopyFile(dir, ConfigCopyFile.RULES).read(),
                "a later document replaces the copy");
        assertFalse(new File(dir, ConfigCopyFile.RULES + ".tmp").exists(), "no temporary file stays");
    }

    @Test
    void rulesAndSettingsUseSeparateFiles() {
        assertTrue(new ConfigCopyFile(dir, ConfigCopyFile.RULES).write("{\"r\":1}"));
        assertTrue(new ConfigCopyFile(dir, ConfigCopyFile.SETTINGS).write("{\"s\":1}"));
        assertEquals("{\"r\":1}", new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
        assertEquals("{\"s\":1}", new ConfigCopyFile(dir, ConfigCopyFile.SETTINGS).read());
    }

    @Test
    void anEmptyOrNonUtf8FileReadsAsNoCopy() throws Exception {
        File f = new File(dir, ConfigCopyFile.RULES);
        Files.write(f.toPath(), new byte[0]);
        assertNull(new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
        Files.write(f.toPath(), new byte[] {'{', (byte) 0xC3, '}'});
        assertNull(new ConfigCopyFile(dir, ConfigCopyFile.RULES).read(), "a cut UTF-8 sequence is not a copy");
    }

    @Test
    void anOversizedFileIsNotReadAndAnOversizedDocumentIsNotWritten() throws Exception {
        File f = new File(dir, ConfigCopyFile.RULES);
        Files.write(f.toPath(), new byte[ConfigCopyFile.MAX_BYTES + 1]);
        assertNull(new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
        String big = "x".repeat(ConfigCopyFile.MAX_BYTES + 1);
        assertFalse(new ConfigCopyFile(dir, ConfigCopyFile.SETTINGS).write(big));
        assertFalse(new File(dir, ConfigCopyFile.SETTINGS).exists());
    }

    @Test
    void anEmptyOrNullDocumentIsNotWritten() {
        assertFalse(new ConfigCopyFile(dir, ConfigCopyFile.RULES).write(null));
        assertFalse(new ConfigCopyFile(dir, ConfigCopyFile.RULES).write(""));
        assertNull(new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
    }

    @Test
    void aMissingDirectoryIsCreated() {
        File sub = new File(dir, "nightjar");
        assertTrue(new ConfigCopyFile(sub, ConfigCopyFile.SETTINGS).write("{}"));
        assertEquals("{}", new ConfigCopyFile(sub, ConfigCopyFile.SETTINGS).read());
    }

    @Test
    void aWriteThatFailsReturnsFalseAndKeepsTheOldCopy() throws Exception {
        assertTrue(new ConfigCopyFile(dir, ConfigCopyFile.RULES).write("{\"old\":1}"));
        File notADir = new File(dir, "file");
        Files.write(notADir.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertFalse(new ConfigCopyFile(notADir, ConfigCopyFile.RULES).write("{}"));
        assertNull(new ConfigCopyFile(notADir, ConfigCopyFile.RULES).read());
        assertEquals("{\"old\":1}", new ConfigCopyFile(dir, ConfigCopyFile.RULES).read());
    }
}
