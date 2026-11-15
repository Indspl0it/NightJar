package dev.nightjar.app.data.audit

/** Actor of an audit record. */
enum class AuditActor {
    USER,
    PRESET,
    SYSTEM,
    ;

    companion object {
        fun from(wire: String?): AuditActor = entries.firstOrNull { it.name == wire } ?: SYSTEM
    }
}

/** Every auditable action (`AuditAction`). */
enum class AuditAction {
    RULE_CREATE,
    RULE_UPDATE,
    RULE_DELETE,
    RULE_REORDER,
    PRESET_CHANGE,
    CLASS_ACTION_CHANGE,
    SAFE_MODE_ON,
    SAFE_MODE_OFF,
    RELEASE,
    BLOCK_HELD,
    DELETE_MESSAGES,
    DELETE_ALL,
    EXPORT,
    MODULE_INSTALL,
    MODULE_UPDATE,
    MODULE_REMOVE,
    FIREWALL_ENABLE,
    FIREWALL_DISABLE,
    OUTGOING_BLOCK,
    OUTGOING_UNBLOCK,
    KNOWN_SENDER_ADD,
    KNOWN_SENDER_REMOVE,
    TRUST_APP_ADD,
    TRUST_APP_REMOVE,
    SETTINGS_CHANGE,
    SELF_TEST_RUN,
    PHONE_RESTART,
    ;

    companion object {
        fun from(wire: String?): AuditAction? = entries.firstOrNull { it.name == wire }
    }
}
