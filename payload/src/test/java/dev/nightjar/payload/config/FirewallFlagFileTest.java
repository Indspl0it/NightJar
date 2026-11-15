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

/** The stored firewall switch survives a restart and a bad file means "no switch". */
class FirewallFlagFileTest {
    @TempDir
    File dir;

    @Test
    void aMissingFileReadsAsNoSwitch() {
        assertNull(new FirewallFlagFile(dir).read());
    }

    @Test
    void aWrittenValueIsReadByANewInstance() {
        assertTrue(new FirewallFlagFile(dir).write(false));
        assertEquals(Boolean.FALSE, new FirewallFlagFile(dir).read(), "a new process reads the stored off");
        assertTrue(new FirewallFlagFile(dir).write(true));
        assertEquals(Boolean.TRUE, new FirewallFlagFile(dir).read(), "a later switch replaces the value");
        assertFalse(new File(dir, FirewallFlagFile.NAME + ".tmp").exists(), "no temporary file stays");
    }

    @Test
    void aBadFileReadsAsNoSwitch() throws Exception {
        File f = new File(dir, FirewallFlagFile.NAME);
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertNull(new FirewallFlagFile(dir).read());
        Files.write(f.toPath(), "10".getBytes(StandardCharsets.UTF_8));
        assertNull(new FirewallFlagFile(dir).read());
        Files.write(f.toPath(), new byte[0]);
        assertNull(new FirewallFlagFile(dir).read());
    }

    @Test
    void aMissingDirectoryIsCreated() {
        File sub = new File(dir, "nightjar");
        assertTrue(new FirewallFlagFile(sub).write(false));
        assertEquals(Boolean.FALSE, new FirewallFlagFile(sub).read());
    }

    @Test
    void aWriteThatFailsReturnsFalseAndDoesNotThrow() throws Exception {
        File notADir = new File(dir, "file");
        Files.write(notADir.toPath(), new byte[] {1});
        assertFalse(new FirewallFlagFile(notADir).write(false));
        assertNull(new FirewallFlagFile(notADir).read());
    }
}
