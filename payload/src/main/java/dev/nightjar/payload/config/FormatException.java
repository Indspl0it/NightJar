package dev.nightjar.payload.config;

/** A configuration document could not be parsed. The holder keeps its previous snapshot. */
public final class FormatException extends Exception {
    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
