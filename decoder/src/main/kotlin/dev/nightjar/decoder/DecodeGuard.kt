package dev.nightjar.decoder

/**
 * Last-resort guard: no decoder may throw, even for adversarial input.
 * Decoders report expected failures as error nodes; this wrapper only covers programming mistakes.
 */
internal inline fun guarded(
    name: String,
    bytes: ByteArray,
    block: () -> Field,
): Field =
    try {
        block()
    } catch (t: Throwable) {
        Field(
            name = name,
            offset = 0,
            length = bytes.size,
            error = "The decoder stopped because of an internal error (${t.javaClass.simpleName}).",
        )
    }

/** Decodes a hex string, returning an error tree for invalid hex. */
internal fun guardedHex(
    name: String,
    text: String,
    block: (ByteArray) -> Field,
): Field {
    val bytes = Hex.decodeOrNull(text)
    if (bytes == null) {
        return Field(name = name, offset = 0, length = 0, error = "The input is not valid hex.")
    }
    return guarded(name, bytes) { block(bytes) }
}
