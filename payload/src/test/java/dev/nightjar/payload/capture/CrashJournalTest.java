package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.Fate;
import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.Transport;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeWorker;
import dev.nightjar.payload.testing.Pdu;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The crash journal skips plain SMS, replays only the files of the dead process, and drops bad files. */
class CrashJournalTest {
    private static final long WAIT_MS = 5_000L;

    @TempDir
    Path tmp;

    private final EventQueue<Observation> queue = new EventQueue<>(64);
    private final Observations observations = new Observations(queue, new FakeWorker(), () -> {});
    private final FakeClock clock = new FakeClock(1_000_000_000L);

    @Test
    void plainPersonToPersonSmsIsRecognised() {
        byte[] plain = Pdu.deliver().build();
        assertTrue(CrashJournal.plainPersonToPerson(plain, Transport.CS));
        assertTrue(CrashJournal.plainPersonToPerson(Hashes.stripSmsc(plain), Transport.IMS));
        assertTrue(CrashJournal.plainPersonToPerson(Pdu.deliver().dcs(0x08).build(), Transport.CS));
    }

    @Test
    void everyOtherPduIsJournaled() {
        assertFalse(CrashJournal.plainPersonToPerson(Pdu.deliver().dcs(0x10).build(), Transport.CS), "class 0");
        assertFalse(CrashJournal.plainPersonToPerson(Pdu.deliver().pid(0x40).build(), Transport.CS), "type 0");
        assertFalse(CrashJournal.plainPersonToPerson(Pdu.deliver().pid(0x7F).build(), Transport.CS), "SIM data");
        assertFalse(CrashJournal.plainPersonToPerson(Pdu.deliver().udh(1, 2, 1, 2948).build(), Transport.CS),
                "user data header");
        assertFalse(CrashJournal.plainPersonToPerson(Pdu.deliver().statusReport().build(), Transport.CS),
                "status report");
        assertFalse(CrashJournal.plainPersonToPerson(new byte[] {0x00, 0x04}, Transport.CS), "too short");
        assertFalse(CrashJournal.plainPersonToPerson(null, Transport.CS));
    }

    @Test
    void leftoverFilesReplayOldestFirstAndAreDeleted() throws IOException {
        byte[] newer = Pdu.deliver().pid(0x40).build();
        byte[] older = Pdu.deliver().dcs(0x10).build();
        File dir = journalDir();
        writeEntry(new File(dir, "m-0000000002000-000001.bin"), 2_000L, 1, Transport.IMS, newer);
        writeEntry(new File(dir, "m-0000000001000-000002.bin"), 1_000L, 0, Transport.CS, older);

        new CrashJournal(tmp.toFile()).replay(observations, clock);

        List<Observation> got = drain();
        assertEquals(4, got.size());
        assertRaw(got.get(0), older, 1_000L, 0, Transport.CS);
        assertSeal(got.get(1), got.get(0).key);
        assertRaw(got.get(2), newer, 2_000L, 1, Transport.IMS);
        assertSeal(got.get(3), got.get(2).key);
        assertEquals(0, journalFiles().length);
    }

    @Test
    void aCorruptFileIsSkippedAndDeleted() throws IOException {
        File dir = journalDir();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "m-0000000001000-000001.bin"))) {
            out.write(new byte[] {1, 2, 3});
        }
        writeEntry(new File(dir, "m-0000000002000-000002.bin"), 2_000L, 0, Transport.CS,
                Pdu.deliver().dcs(0x10).build());

        new CrashJournal(tmp.toFile()).replay(observations, clock);

        List<Observation> got = drain();
        assertEquals(2, got.size());
        assertEquals(2_000L, got.get(0).wallMs);
        assertEquals(0, journalFiles().length);
    }

    @Test
    void filesOfThisProcessAreNotReplayedButTheNextProcessGetsThem() throws Exception {
        CrashJournal journal = new CrashJournal(tmp.toFile());
        byte[] pdu = Pdu.deliver().dcs(0x10).build();
        journal.append(pdu, Transport.CS, 0, 5_000L);
        awaitFileCount(1);

        journal.replay(observations, clock);
        assertTrue(drain().isEmpty(), "this process wrote the file");
        assertEquals(1, journalFiles().length);

        new CrashJournal(tmp.toFile()).replay(observations, clock);
        List<Observation> got = drain();
        assertEquals(2, got.size());
        assertRaw(got.get(0), pdu, 5_000L, 0, Transport.CS);
        assertEquals(0, journalFiles().length);
    }

    @Test
    void aPlainSmsIsNeverWritten() throws Exception {
        CrashJournal journal = new CrashJournal(tmp.toFile());
        journal.append(Pdu.deliver().build(), Transport.CS, 0, 1_000L);
        // The journal thread runs in order: when the second file exists, the first append is done.
        journal.append(Pdu.deliver().dcs(0x10).build(), Transport.CS, 0, 2_000L);
        awaitFileCount(1);
        assertTrue(journalFiles()[0].getName().startsWith("m-0000000002000-"));
    }

    @Test
    void deliveredDeletesTheFile() throws Exception {
        CrashJournal journal = new CrashJournal(tmp.toFile());
        byte[] pdu = Pdu.deliver().dcs(0x10).build();
        journal.append(pdu, Transport.CS, 0, 1_000L);
        awaitFileCount(1);
        journal.delivered(Hashes.tpduKey(Hashes.stripSmsc(pdu)));
        awaitFileCount(0);
    }

    private static void assertRaw(Observation o, byte[] pdu, long wallMs, int phoneId, Transport transport) {
        assertEquals(Observation.Kind.RAW, o.kind);
        assertArrayEquals(pdu, o.pdu);
        assertEquals(wallMs, o.wallMs);
        assertEquals(phoneId, o.phoneId);
        assertEquals(transport, o.transport);
        assertEquals(Hashes.tpduKey(Hashes.stripSmsc(pdu)), o.key);
    }

    private static void assertSeal(Observation o, String key) {
        assertEquals(Observation.Kind.SEAL, o.kind);
        assertEquals(key, o.key);
        assertEquals(Fate.PHONE_STOPPED, o.fate);
    }

    private List<Observation> drain() {
        List<Observation> out = new ArrayList<>();
        for (Observation o = queue.poll(); o != null; o = queue.poll()) out.add(o);
        return out;
    }

    private File journalDir() {
        File dir = new File(tmp.toFile(), "journal");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }

    private File[] journalFiles() {
        File[] files = new File(tmp.toFile(), "journal").listFiles((d, n) -> n.endsWith(".bin"));
        return files == null ? new File[0] : files;
    }

    private void awaitFileCount(int expected) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (journalFiles().length != expected && System.currentTimeMillis() < end) Thread.sleep(10);
        assertEquals(expected, journalFiles().length);
    }

    /** Writes one entry in the journal format: wall time, phone id, transport name, PDU length, PDU. */
    private static void writeEntry(File f, long wallMs, int phoneId, Transport transport, byte[] pdu)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeLong(wallMs);
        out.writeInt(phoneId);
        out.writeUTF(transport.name());
        out.writeInt(pdu.length);
        out.write(pdu);
        out.flush();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(buf.toByteArray());
        }
    }
}
