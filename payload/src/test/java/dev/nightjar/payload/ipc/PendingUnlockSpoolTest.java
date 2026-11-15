package dev.nightjar.payload.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.capture.PendingUnlockStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pending-unlock spool: append, remove, caps, compaction, PDU stripping and corruption handling. */
class PendingUnlockSpoolTest {

    private static PendingUnlockStore.Entry entry(String eventId, int revision, long createdAt) {
        return new PendingUnlockStore.Entry("k-" + eventId, eventId, revision, createdAt, "CL-WAP-SISL",
                "{\"eventId\":\"" + eventId + "\",\"revision\":" + revision + "}");
    }

    /** A spooled class with PDUs (kept on disk), about {@code size} bytes of synthetic hex. */
    private static PendingUnlockStore.Entry bigEntry(String eventId, long createdAt, int size) {
        String pdu = "00".repeat(size / 2);
        return new PendingUnlockStore.Entry("k-" + eventId, eventId, 1, createdAt, "CL-WAP-SISL",
                "{\"eventId\":\"" + eventId + "\",\"pdus\":[\"" + pdu + "\"]}");
    }

    @Test
    void C12_putThenLoadRoundTrips(@TempDir Path dir) {
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "boot-1", "1.0.0", () -> now, null);
        spool.put(entry("e1", 1, now));

        assertTrue(spool.file().exists());
        List<PendingUnlockStore.Entry> loaded =
                new PendingUnlockSpool(dir.toFile(), "boot-2", "1.0.0", () -> now, null).load();
        assertEquals(1, loaded.size());
        PendingUnlockStore.Entry e = loaded.get(0);
        assertEquals("e1", e.eventId());
        assertEquals(1, e.revision());
        assertEquals("k-e1", e.key());
        assertEquals("CL-WAP-SISL", e.predictedClass());
        assertTrue(e.eventJson().contains("\"e1\""));
    }

    @Test
    void C12_removeDeletesTheFileWhenEmpty(@TempDir Path dir) {
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        spool.put(entry("e1", 1, now));
        spool.put(entry("e2", 1, now));
        spool.remove("e1", 1);
        assertEquals(1, spool.size());
        assertTrue(spool.file().exists());
        spool.remove("e2", 1);
        assertFalse(spool.file().exists());
    }

    @Test
    void C12_removeIgnoresAnUnknownRevision(@TempDir Path dir) {
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        spool.put(entry("e1", 1, now));
        spool.remove("e1", 2);
        assertEquals(1, spool.size());
    }

    @Test
    void C12_capEvictsTheOldestEntry(@TempDir Path dir) {
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        for (int i = 0; i < PendingUnlockSpool.MAX_ENTRIES + 3; i++) {
            spool.put(entry("e" + i, 1, now));
        }
        assertEquals(PendingUnlockSpool.MAX_ENTRIES, spool.size());
        List<PendingUnlockStore.Entry> loaded =
                new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null).load();
        assertEquals(PendingUnlockSpool.MAX_ENTRIES, loaded.size());
        assertEquals("e3", loaded.get(0).eventId());
    }

    @Test
    void C12_loadReturnsEntriesOlderThan24hForTheCorrelatorToExpire(@TempDir Path dir) {
        // The correlator emits FT-UNLOCK-EXPIRED for an old entry, so the spool must not drop it silently.
        long now = 1_762_330_000_000L;
        long day = 24L * 60 * 60 * 1000;
        PendingUnlockSpool writer = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        writer.put(entry("old", 1, now - day - 1));
        writer.put(entry("fresh", 1, now - 1_000L));

        List<PendingUnlockStore.Entry> loaded =
                new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null).load();
        assertEquals(2, loaded.size());
        assertEquals("old", loaded.get(0).eventId());
        assertEquals("fresh", loaded.get(1).eventId());
    }

    @Test
    void C12_byteLimitEvictsOnlyAsManyOldEntriesAsNeeded(@TempDir Path dir) {
        // The old loop tested the file length, which does not change, and evicted all entries but one.
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        int count = 60;
        for (int i = 0; i < count; i++) spool.put(bigEntry("e" + i, now, 50_000));
        int kept = spool.size();
        assertTrue(kept > 30 && kept < count, "kept " + kept);
        assertTrue(spool.file().length() <= PendingUnlockSpool.MAX_BYTES + 60_000, "file " + spool.file().length());
        List<PendingUnlockStore.Entry> loaded =
                new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null).load();
        assertEquals(kept, loaded.size());
        assertEquals("e" + (count - 1), loaded.get(loaded.size() - 1).eventId());
    }

    @Test
    void C12_compactionDropsTombstonesWithoutEvictingLiveEntries(@TempDir Path dir) {
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        spool.put(bigEntry("keep1", now, 50_000));
        spool.put(bigEntry("keep2", now, 50_000));
        for (int i = 0; i < 100; i++) {
            spool.put(bigEntry("churn" + i, now, 50_000));
            spool.remove("churn" + i, 1);
        }
        assertEquals(2, spool.size());
        assertTrue(spool.file().length() < PendingUnlockSpool.MAX_BYTES, "file " + spool.file().length());
        List<PendingUnlockStore.Entry> loaded =
                new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null).load();
        assertEquals(2, loaded.size());
        assertEquals("keep1", loaded.get(0).eventId());
    }

    @Test
    void C12_aJournalWithAnotherVersionHeaderIsDeleted(@TempDir Path dir) throws IOException {
        File file = new File(dir.toFile(), "pending_unlock.spool");
        String journal = "{\"spool\":3,\"bootId\":\"b\"}\n"
                + "{\"op\":\"put\",\"eventId\":\"e1\",\"revision\":1,\"key\":\"k\",\"createdAtWall\":1}\n";
        Files.write(file.toPath(), journal.getBytes(StandardCharsets.UTF_8));
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", System::currentTimeMillis, null);
        assertTrue(spool.load().isEmpty());
        assertTrue(spool.lastLoadCorrupt());
        assertFalse(file.exists());
    }

    @Test
    void H6_pdusAreStrippedForEveryClassOnTheNormalDeliveryPath() {
        String json = "{\"eventId\":\"e1\",\"pdus\":[\"AA\"],\"parts\":[{\"seq\":1}]}";
        for (String cls : new String[] {"", null, "CL-NORMAL", "CL-CLASS3", "CL-BINARY", "CL-RETURN-CALL",
                "CL-NOT-A-CLASS"}) {
            String out = PendingUnlockSpool.contentFree(cls, json);
            assertFalse(out.contains("pdus") || out.contains("parts"), cls + ": " + out);
            assertTrue(out.contains("e1"), cls + ": " + out);
        }
        assertEquals(json, PendingUnlockSpool.contentFree("CL-WAP-SISL", json));
        assertEquals("{}", PendingUnlockSpool.contentFree("CL-NORMAL", "{not json"));
    }

    @Test
    void C12_corruptFileIsDeletedAndReported(@TempDir Path dir) throws IOException {
        File file = new File(dir.toFile(), "pending_unlock.spool");
        Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", System::currentTimeMillis, null);
        assertTrue(spool.load().isEmpty());
        assertTrue(spool.lastLoadCorrupt());
        assertFalse(file.exists());
        assertFalse(spool.disabled());
    }

    @Test
    void C12_unknownSpoolVersionIsDeleted(@TempDir Path dir) throws IOException {
        File file = new File(dir.toFile(), "pending_unlock.spool");
        Files.write(file.toPath(),
                "{\"spool\":99,\"entries\":[]}".getBytes(StandardCharsets.UTF_8));
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", System::currentTimeMillis, null);
        assertTrue(spool.load().isEmpty());
        assertTrue(spool.lastLoadCorrupt());
        assertFalse(file.exists());
    }

    @Test
    void C12_clearEmptiesAndDeletes(@TempDir Path dir) {
        long now = 1_762_330_000_000L;
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", () -> now, null);
        spool.put(entry("e1", 1, now));
        spool.clear();
        assertEquals(0, spool.size());
        assertFalse(spool.file().exists());
        assertTrue(spool.load().isEmpty());
    }

    @Test
    void C12_writeFailureDisablesTheSpoolWithoutThrowing(@TempDir Path dir) throws IOException {
        // A regular file used as the directory makes mkdirs and the temp write fail.
        File notADirectory = new File(dir.toFile(), "nightjar-spool-blocker");
        Files.write(notADirectory.toPath(), new byte[] {1});
        PendingUnlockSpool spool = new PendingUnlockSpool(notADirectory, "b", "1", System::currentTimeMillis, null);
        spool.put(entry("e1", 1, System.currentTimeMillis()));
        assertTrue(spool.disabled());
        assertTrue(spool.load().isEmpty());
        assertFalse(spool.lastLoadCorrupt());
    }

    @Test
    void C12_nullEntryIsIgnored(@TempDir Path dir) {
        PendingUnlockSpool spool = new PendingUnlockSpool(dir.toFile(), "b", "1", System::currentTimeMillis, null);
        spool.put((PendingUnlockStore.Entry) null);
        assertEquals(0, spool.size());
        assertFalse(spool.file().exists());
    }
}
