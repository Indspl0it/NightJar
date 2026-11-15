package dev.nightjar.payload.ipc;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Wire;
import dev.nightjar.payload.capture.PendingUnlockStore;
import dev.nightjar.payload.capture.Sealer;
import dev.nightjar.payload.log.PLog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pending-unlock spool: the device-protected file that keeps
 * {@code FT-PENDING-UNLOCK} events alive across a phone-process restart.
 *
 * <p>Path: {@code <dir>/pending_unlock.spool} with {@code dir} = the phone process's device-protected
 * {@code files/nightjar}; mode 0600. Format {@link Schemas#SPOOL}: a journal of JSON lines. One header line has
 * {@code spool}, plus {@code bootId}, {@code payloadVersion} and {@code writtenAtWall} for diagnostics. Then one
 * {@code put} or {@code remove} record follows per change. A change appends one line with no {@code fsync}. A
 * compaction rewrites the live entries through a temp file, {@code fsync} and an atomic rename; it runs after a
 * migration, when the journal is much longer than the live set, and when the file is larger than {@link #MAX_BYTES}.
 * Limits: {@link #MAX_ENTRIES} entries and {@link #MAX_BYTES}, oldest evicted first. An entry evicted by the byte
 * limit stays in the correlator's RAM, so it loses only the restart protection.
 *
 * <p>{@link #load()} returns every entry it can read: the correlator owns the 24 h expiry and its own cap, and emits
 * {@code FT-UNLOCK-EXPIRED} for each entry it does not keep.
 *
 * <p>Fail open: any I/O error disables the spool for this boot (the caller continues RAM-only) and is logged once. A
 * file with a different {@code spool} version or an unparseable line is deleted and reported through
 * {@link #lastLoadCorrupt()}.
 *
 * <p>Thread: payload worker only (never a hook thread).
 */
public final class PendingUnlockSpool implements PendingUnlockStore {

    /** limits. */
    public static final int MAX_ENTRIES = 500;

    public static final long MAX_BYTES = 2L * 1024 * 1024;

    static final String FILE_NAME = "pending_unlock.spool";
    private static final String TMP_NAME = "pending_unlock.spool.tmp";
    private static final String TAG = "Spool";

    private static final String KEY_SPOOL = "spool";
    private static final String KEY_BOOT_ID = "bootId";
    private static final String KEY_WRITTEN_AT = "writtenAtWall";
    private static final String KEY_PAYLOAD_VERSION = dev.nightjar.contract.Ipc.Key.PAYLOAD_VERSION;
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_ENTRY_KEY = "key";
    private static final String KEY_ENTRY_EVENT_ID = "eventId";
    private static final String KEY_ENTRY_REVISION = "revision";
    private static final String KEY_ENTRY_CREATED = "createdAtWall";
    private static final String KEY_ENTRY_CLASS = "predictedClass";
    private static final String KEY_ENTRY_EVENT = "event";

    private final File dir;
    private final File file;
    private final File tmp;
    private final String bootId;
    private final String payloadVersion;
    private final LongSupplier wallClock;
    private final PLog log;

    /** Insertion ordered; key = {@code eventId:revision}. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private boolean disabled;
    private boolean corrupt;
    private boolean warned;

    /**
     * @param dir the phone process's device-protected {@code files/nightjar} directory
     * @param bootId written to the header for diagnostics only; never compared
     */
    public PendingUnlockSpool(File dir, String bootId, String payloadVersion, LongSupplier wallClock, PLog log) {
        this.dir = dir;
        this.file = new File(dir, FILE_NAME);
        this.tmp = new File(dir, TMP_NAME);
        this.bootId = bootId == null ? "" : bootId;
        this.payloadVersion = payloadVersion == null ? "" : payloadVersion;
        this.wallClock = wallClock == null ? System::currentTimeMillis : wallClock;
        this.log = log;
    }

    /**
     * Reads the spool and returns every live entry (oldest first), also entries older than 24 h and entries over the
     * cap: the correlator expires them with {@code FT-UNLOCK-EXPIRED}.
     */
    @Override
    public List<Entry> load() {
        entries.clear();
        corrupt = false;
        if (disabled || !file.exists()) return List.of();

        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            disable("read failed", e);
            return List.of();
        }

        boolean migrated = false;
        int records = 0;
        try {
            String[] lines = text.split("\n");
            for (String line : lines) {
                if (line.isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                if (o.has("entries")) {
                    // Version-1 document (one JSON object): migrate to the journal.
                    replayOldDocument(o);
                    migrated = true;
                    break;
                }
                if (o.has(KEY_SPOOL) && !o.has(KEY_ENTRY_EVENT_ID)) {
                    // Header line: a different journal version is not ours to read.
                    if (o.optInt(KEY_SPOOL, -1) != Schemas.SPOOL) throw new JSONException("unknown spool version");
                    continue;
                }
                records++;
                String eventId = o.optString(KEY_ENTRY_EVENT_ID, "");
                if (eventId.isEmpty()) continue;
                if ("remove".equals(o.optString("op", "put"))) {
                    entries.remove(mapKey(eventId, o.optInt(KEY_ENTRY_REVISION, 1)));
                    continue;
                }
                JSONObject event = o.optJSONObject(KEY_ENTRY_EVENT);
                entries.put(mapKey(eventId, o.optInt(KEY_ENTRY_REVISION, 1)), new Entry(
                        o.optString(KEY_ENTRY_KEY, ""),
                        eventId,
                        o.optInt(KEY_ENTRY_REVISION, 1),
                        o.optLong(KEY_ENTRY_CREATED, 0L),
                        o.optString(KEY_ENTRY_CLASS, ""),
                        event == null ? "{}" : event.toString()));
            }
        } catch (Exception e) {
            // JSONException (checked in the Android stub) or a RuntimeException.
            dropCorrupt(e);
            return List.of();
        }

        // A journal from an older payload can hold more than the cap: keep the newest, as put() does.
        boolean overCap = false;
        while (entries.size() > MAX_ENTRIES && removeEldest() != null) overCap = true;
        if (migrated || overCap || records > entries.size() * 2 + 64 || file.length() > MAX_BYTES) {
            compact();
        }
        return new ArrayList<>(entries.values());
    }

    @Override
    public void put(Entry entry) {
        if (entry == null) return;
        put(entry.key(), entry.eventId(), entry.revision(), entry.createdAtWall(), entry.predictedClass(),
                entry.eventJson());
    }

    /** Adds or replaces one entry and appends it to the journal. */
    public void put(String key, String eventId, int revision, long createdAtWall, String predictedClass,
            String eventJson) {
        if (disabled) return;
        if (eventId == null || eventId.isEmpty()) return;
        String cls = predictedClass == null ? "" : predictedClass;
        entries.put(mapKey(eventId, revision),
                new Entry(key == null ? "" : key, eventId, revision, createdAtWall, cls,
                        contentFree(cls, eventJson)));
        while (entries.size() > MAX_ENTRIES) {
            // The correlator keeps the same cap and emits FT-UNLOCK-EXPIRED for what it evicts. VR01-C04: the eviction goes
            // to the journal too, or the next load brings the entry back.
            Entry evicted = removeEldest();
            if (evicted == null) break;
            appendRemove(evicted.eventId(), evicted.revision());
        }
        appendPut(entries.get(mapKey(eventId, revision)));
    }

    /** Removes one revision; deletes the file once the spool is empty. */
    @Override
    public void remove(String eventId, int revision) {
        if (disabled) return;
        if (entries.remove(mapKey(eventId, revision)) == null) return;
        if (entries.isEmpty()) {
            deleteFile();
            return;
        }
        appendRemove(eventId, revision);
    }

    /** Empties the spool and deletes the file; for tests. */
    void clear() {
        entries.clear();
        deleteFile();
    }

    /**
     * A person-to-person SMS body never reaches this unencrypted device-protected file. A pending event
     * of a class on the normal delivery path ({@link Sealer#normalDeliveryPath(ClassId)}: CL-NORMAL, CL-CLASS3,
     * CL-BINARY and the others), or one with no known class, keeps its key, class and metadata; the PDUs and the parts
     * stay in RAM only and the restore reports the event as {@code FT-UNLOCK-EXPIRED} if the process dies before unlock.
     *
     * <p>Every other class is a message Nightjar already captures and exports, so its PDUs are
     * spooled as-is (they are what makes release after unlock possible).
     */
    static String contentFree(String predictedClass, String eventJson) {
        if (eventJson == null || eventJson.isEmpty()) return "{}";
        ClassId c = predictedClass == null || predictedClass.isEmpty()
                ? null
                : Wire.parse(ClassId.class, predictedClass, null);
        if (c != null && !Sealer.normalDeliveryPath(c)) return eventJson;
        try {
            JSONObject o = new JSONObject(eventJson);
            o.remove("pdus");
            o.remove("parts");
            return o.toString();
        } catch (Exception e) {
            // Unparseable: store nothing rather than risk a body on disk.
            return "{}";
        }
    }

    /** Live entries; for tests. */
    int size() {
        return entries.size();
    }

    /** True when the spool is unusable for this boot and the caller must stay RAM-only; for tests. */
    boolean disabled() {
        return disabled;
    }

    /** True when the last {@link #load()} deleted an unknown or unparseable file; for tests. */
    boolean lastLoadCorrupt() {
        return corrupt;
    }

    /** The journal file; for tests. */
    File file() {
        return file;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Replays the version-1 single-object document (one {@code entries} array) into the map.
     */
    private void replayOldDocument(JSONObject root) throws JSONException {
        if (root.optInt(KEY_SPOOL, -1) != 1) throw new JSONException("unknown spool document version");
        JSONArray array = root.optJSONArray(KEY_ENTRIES);
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String eventId = o.optString(KEY_ENTRY_EVENT_ID, "");
            if (eventId.isEmpty()) continue;
            JSONObject event = o.optJSONObject(KEY_ENTRY_EVENT);
            entries.put(mapKey(eventId, o.optInt(KEY_ENTRY_REVISION, 1)), new Entry(
                    o.optString(KEY_ENTRY_KEY, ""),
                    eventId,
                    o.optInt(KEY_ENTRY_REVISION, 1),
                    o.optLong(KEY_ENTRY_CREATED, 0L),
                    o.optString(KEY_ENTRY_CLASS, ""),
                    event == null ? "{}" : event.toString()));
        }
    }

    // ------------------------------------------------------------- journal I/O

    /**
     * One appended put record; no rewrite, no fsync (the compact pass fsyncs). When the journal passes
     * {@link #MAX_BYTES}, a compaction drops the tombstones and old revisions, and evicts the eldest entries only if
     * the live set alone is still too large.
     */
    private void appendPut(Entry entry) {
        if (disabled || entry == null) return;
        try {
            JSONObject o = record(entry);
            o.put("op", "put");
            append(o.toString());
        } catch (Exception e) {
            disable("append failed", e);
            return;
        }
        if (file.length() > MAX_BYTES) compact();
    }

    /** One appended tombstone. */
    private void appendRemove(String eventId, int revision) {
        if (disabled) return;
        try {
            JSONObject o = new JSONObject();
            o.put("op", "remove");
            o.put(KEY_ENTRY_EVENT_ID, eventId);
            o.put(KEY_ENTRY_REVISION, revision);
            append(o.toString());
        } catch (Exception e) {
            disable("append failed", e);
        }
    }

    private void append(String line) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("cannot create spool directory");
        }
        boolean fresh = !file.exists();
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            if (fresh) writeHeader(out);
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (fresh) out.getFD().sync();
        }
        restrictToOwner(file.toPath());
    }

    /**
     * Rewrites the journal as a header plus one put record per live entry, atomically. When the live records
     * alone are larger than {@link #MAX_BYTES}, the eldest entries are evicted first; the newest entry always stays.
     * Runs after a migration, when the journal outgrew the live set and when the file passed the byte limit.
     */
    private void compact() {
        if (disabled) return;
        try {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                disable("cannot create directory", null);
                return;
            }
            java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
            writeHeader(header);
            List<byte[]> lines = new ArrayList<>(entries.size());
            long total = header.size();
            for (Entry entry : entries.values()) {
                JSONObject o = record(entry);
                o.put("op", "put");
                byte[] line = (o + "\n").getBytes(StandardCharsets.UTF_8);
                lines.add(line);
                total += line.length;
            }
            int evict = 0;
            while (total > MAX_BYTES && lines.size() - evict > 1) {
                total -= lines.get(evict).length;
                evict++;
            }
            if (evict > 0) {
                Iterator<String> it = entries.keySet().iterator();
                for (int i = 0; i < evict && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
                if (log != null) {
                    log.wLimited("spool-evict", TAG, "Spool is larger than the byte limit. The spool evicts the "
                            + evict + " oldest entries.");
                }
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream((int) Math.min(total, MAX_BYTES));
            header.writeTo(buffer);
            for (int i = evict; i < lines.size(); i++) buffer.write(lines.get(i));
            writeAtomically(buffer.toByteArray());
        } catch (Exception e) {
            disable("compaction failed", e);
        }
    }

    private JSONObject record(Entry entry) throws JSONException {
        JSONObject o = new JSONObject();
        o.put(KEY_ENTRY_KEY, entry.key());
        o.put(KEY_ENTRY_EVENT_ID, entry.eventId());
        o.put(KEY_ENTRY_REVISION, entry.revision());
        o.put(KEY_ENTRY_CREATED, entry.createdAtWall());
        o.put(KEY_ENTRY_CLASS, entry.predictedClass());
        JSONObject event;
        try {
            event = new JSONObject(entry.eventJson());
        } catch (Exception e) {
            event = new JSONObject();
        }
        o.put(KEY_ENTRY_EVENT, event);
        return o;
    }

    private void writeHeader(java.io.OutputStream out) throws IOException {
        JSONObject header = new JSONObject();
        try {
            header.put(KEY_SPOOL, Schemas.SPOOL);
            header.put(KEY_BOOT_ID, bootId);
            header.put(KEY_PAYLOAD_VERSION, payloadVersion);
            header.put(KEY_WRITTEN_AT, wallClock.getAsLong());
        } catch (Exception e) {
            throw new IOException("cannot build spool header", e);
        }
        out.write((header + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        restrictToOwner(tmp.toPath());
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        restrictToOwner(file.toPath());
    }

    private static void restrictToOwner(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem (JVM tests on Windows): the DE directory already restricts access.
        }
    }

    private Entry removeEldest() {
        String firstKey = null;
        for (String key : entries.keySet()) {
            firstKey = key;
            break;
        }
        return firstKey == null ? null : entries.remove(firstKey);
    }

    private void deleteFile() {
        try {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(tmp.toPath());
        } catch (IOException | RuntimeException e) {
            warnOnce("Cannot delete the spool file: " + e.getClass().getSimpleName());
        }
    }

    private void dropCorrupt(Throwable cause) {
        corrupt = true;
        if (log != null) {
            log.w(TAG, "Spool file is not valid. The spool deletes it: " + cause.getClass().getSimpleName());
        }
        deleteFile();
    }

    private void disable(String what, Throwable cause) {
        disabled = true;
        String suffix = cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")";
        warnOnce("Spool stops for this boot: " + what + suffix + ".");
    }

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        if (log != null) log.w(TAG, message);
    }

    private static String mapKey(String eventId, int revision) {
        return eventId + ":" + revision;
    }
}
