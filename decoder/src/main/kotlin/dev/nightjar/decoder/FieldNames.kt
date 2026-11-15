package dev.nightjar.decoder

/**
 * Canonical field-tree keys.
 *
 * These are node names and JSON object keys of [Field] trees, not IPC Bundle keys; the decoder stays
 * free of the `:contract` module, so the literals live here and are waived in the contract check.
 */
object FieldNames {
    const val ERROR: String = "error" // contract-ok: decoder field-tree node name, not an IPC key
    const val NAME: String = "name" // contract-ok: decoder field-tree node name, not an IPC key
    const val VALUE: String = "value" // contract-ok: decoder field-tree node name, not an IPC key
    const val OFFSET: String = "offset" // contract-ok: decoder field-tree node name, not an IPC key
    const val LENGTH: String = "length" // contract-ok: decoder field-tree node name, not an IPC key
    const val CHILDREN: String = "children" // contract-ok: decoder field-tree node name, not an IPC key
}
