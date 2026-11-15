package dev.nightjar.payload.capture;

import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.Transport;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Write-ahead record of the raw PDUs, so a message that crashes the phone process is still captured.
 *
 * <p>A malformed message (a bad WAP push, for one) can kill {@code com.android.phone} milliseconds after the capture
 * hook ran, before the in-memory pipeline has delivered anything to the app. Each PDU is therefore written to a small
 * file, and deleted when the correlator emits its event ({@link #delivered(String)}), or at the latest
 * {@link #KEEP_MS} after it arrived if the process is still alive. When the payload starts, the files of the process
 * that died are replayed into the pipeline, so nothing is skipped because it was the one that caused the crash.
 *
 * <p>A plain person-to-person SMS (SMS-DELIVER, TP-PID 0, no user data header, general data coding without a
 * message class) is never written: its body must not reach this unencrypted device-protected directory, and such a
 * message is not the kind that crashes the phone process.
 *
 * <p>Only the files that exist when the journal is created are replayed: files of this process are never seen as
 * leftovers. The replay assumes 3GPP framing (no CDMA on the target devices).
 *
 * <p>Thread: {@link #append} on any hook thread, never blocks (the write runs on the journal thread);
 * {@link #delivered} on the worker; {@link #replay} once, from the worker. The journal thread is a daemon that lives
 * as long as the process.
 */
public final class CrashJournal {
    /** A message older than this that is still on disk was lost with its process. */
    static final long KEEP_MS = 30_000L;
    private static final int MAX_FILES = 64;
    private static final int MAX_PDU = 4096;
    private static final String PREFIX = "m-";
    private static final String SUFFIX = ".bin";

    private final File dir;
    /** Files of this process that are queued or on disk; bounds the journal at {@link #MAX_FILES}. */
    private final AtomicInteger live = new AtomicInteger();
    private final AtomicInteger seq = new AtomicInteger();
    /** Files of the process that died, captured before this process wrote anything. */
    private final File[] leftovers;
    /** Correlation key to the files of this process; journal thread only. */
    private final Map<String, List<File>> byKey = new HashMap<>();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nj-journal");
                t.setDaemon(true);
                return t;
            });

    public CrashJournal(File parent) {
        this.dir = new File(parent, "journal");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
        this.leftovers = files == null ? new File[0] : files;
    }

    /** Persists one PDU on the journal thread; never throws and never blocks the calling hook thread. */
    public void append(byte[] pdu, Transport transport, int phoneId, long wallMs) {
        if (pdu == null || pdu.length == 0 || pdu.length > MAX_PDU) return;
        try {
            if (plainPersonToPerson(pdu, transport)) return;
            if (live.incrementAndGet() > MAX_FILES) {
                live.decrementAndGet();
                return;
            }
            byte[] copy = pdu.clone();
            int n = seq.incrementAndGet();
            worker.execute(() -> write(copy, transport, phoneId, wallMs, n));
        } catch (Throwable ignored) {
            // Capturing must never fail because the journal could not be written.
        }
    }

    /** The event of {@code key} was emitted: its PDU is safe, so its file goes. Never throws. */
    public void delivered(String key) {
        if (key == null) return;
        try {
            worker.execute(() -> {
                List<File> files = byKey.remove(key);
                if (files == null) return;
                for (File f : files) delete(f);
            });
        } catch (Throwable ignored) {
            // The timer deletes the file instead.
        }
    }

    private void write(byte[] pdu, Transport transport, int phoneId, long wallMs, int n) {
        File f = new File(dir, String.format(Locale.ROOT, "%s%013d-%06d%s", PREFIX, wallMs, n % 1_000_000, SUFFIX));
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(pdu.length + 48);
            DataOutputStream out = new DataOutputStream(buf);
            out.writeLong(wallMs);
            out.writeInt(phoneId);
            out.writeUTF(transport == null ? "" : transport.name());
            out.writeInt(pdu.length);
            out.write(pdu);
            out.flush();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(buf.toByteArray());
            }
            String key = keyFor(pdu);
            byKey.computeIfAbsent(key, k -> new ArrayList<>(1)).add(f);
            worker.schedule(() -> {
                List<File> files = byKey.get(key);
                if (files != null) {
                    files.remove(f);
                    if (files.isEmpty()) byKey.remove(key);
                }
                delete(f);
            }, KEEP_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            live.decrementAndGet();
        }
    }

    /**
     * Journal thread only. A failed delete recounts the directory, so a stuck file cannot block the journal for
     * ever.
     */
    private void delete(File f) {
        if (!f.exists()) return;
        if (f.delete()) {
            live.decrementAndGet();
            return;
        }
        String[] names = dir.list((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
        live.set(names == null ? 0 : names.length);
    }

    /** Replays what the previous process left behind, oldest first, then removes those files. */
    public void replay(Observations out, dev.nightjar.payload.env.Clock clock) {
        File[] files = leftovers.clone();
        java.util.Arrays.sort(files);
        for (File f : files) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                long wall = in.readLong();
                int phoneId = in.readInt();
                String tr = in.readUTF();
                int len = in.readInt();
                if (len <= 0 || len > MAX_PDU) continue;
                byte[] pdu = new byte[len];
                in.readFully(pdu);
                Transport transport = tr.isEmpty() ? null : Transport.valueOf(tr);
                String key = keyFor(pdu);
                out.offer(Observation.raw(key, Hashes.tpduKey(pdu), pdu, "3gpp", transport, phoneId, -1, -1, 0,
                        false, clock.elapsedNs(), wall));
                // Nothing downstream will ever report on this message, so give it a fate or it is never emitted.
                // The true outcome is that the phone process stopped before the message had one.
                out.offer(Observation.seal(key, dev.nightjar.contract.Fate.PHONE_STOPPED,
                        "Message recovered after the phone service restarted.", clock.elapsedNs()));
            } catch (Throwable ignored) {
                // A truncated file is the one being written when the process died: nothing more can be recovered.
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** The correlation key of the capture hooks ({@code CaptureHooks.keyFor}). */
    private static String keyFor(byte[] pdu) {
        return Hashes.tpduKey(Hashes.stripSmsc(pdu));
    }

    /**
     * True for a plain person-to-person SMS-DELIVER (3GPP TS 23.040): TP-MTI 00, TP-UDHI 0, TP-PID 0x00, and
     * TP-DCS in the general data coding group without a message class and not compressed (0x00 to 0x0F, TS 23.038
     * §4). A PDU this check cannot read is not plain, so it is journaled.
     */
    static boolean plainPersonToPerson(byte[] pdu, Transport transport) {
        byte[] tpdu = transport == Transport.IMS ? pdu : Hashes.stripSmsc(pdu);
        if (tpdu == null || tpdu.length < 4) return false;
        int first = tpdu[0] & 0xFF;
        if ((first & 0x03) != 0 || (first & 0x40) != 0) return false;
        int digits = tpdu[1] & 0xFF;
        if (digits > 20) return false;
        int pid = 2 + 1 + (digits + 1) / 2; // address length, type of address, address octets
        if (pid + 1 >= tpdu.length) return false;
        int dcs = tpdu[pid + 1] & 0xFF;
        return (tpdu[pid] & 0xFF) == 0 && dcs <= 0x0F;
    }
}
