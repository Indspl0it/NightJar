package dev.nightjar.contract;

/** Contract versions. */
public final class Schemas {
    private Schemas() {}

    public static final int RULES = 1;
    public static final int SETTINGS = 1;
    public static final int PROFILES = 1;

    /**
     * Pending-unlock spool. 2: a journal of JSON lines, a header line {@code {"spool":2,…}} and then one {@code put} or
     * {@code remove} record per line. 1 was one JSON object with an {@code entries} array; the payload reads it only
     * to migrate it to the journal.
     */
    public static final int SPOOL = 2;
}
