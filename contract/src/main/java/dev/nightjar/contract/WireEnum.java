package dev.nightjar.contract;

/** An enum with a stable wire name used in JSON, Bundles and the database. */
public interface WireEnum {
    /** The exact wire spelling. Never change a published value. */
    String wire();
}
