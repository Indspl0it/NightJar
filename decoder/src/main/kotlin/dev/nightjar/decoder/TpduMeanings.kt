package dev.nightjar.decoder

/**
 * The meaning texts of the TPDU code points: TP-PID (TS 23.040 9.2.3.9), TP-ST (9.2.3.15) and TP-CT (9.2.3.19).
 * They are separate from [Tpdu] so that the parser object keeps to the function limit (VR01-D14).
 */
internal object TpduMeanings {
    /** TS 23.040 9.2.3.9. */
    fun pidMeaning(pid: Int): String =
        when {
            pid == 0x00 -> "normal"
            pid and 0xE0 == 0x00 -> "SME-to-SME protocol"
            pid and 0xE0 == 0x20 -> telematicPid(pid and 0x1F)
            pid and 0xC0 == 0x40 -> shortMessagePid(pid)
            pid and 0xC0 == 0x80 -> "reserved"
            else -> "SC-specific"
        }

    /** TS 23.040 9.2.3.9, bit 5 = 1: telematic interworking, bits 4-0 = device type. */
    private fun telematicPid(device: Int): String =
        when (device) {
            0x00 -> "telematic: implicit"
            0x01 -> "telematic: telex"
            0x02 -> "telematic: group 3 telefax"
            0x03 -> "telematic: group 4 telefax"
            0x04 -> "telematic: voice telephone"
            0x05 -> "telematic: ERMES"
            0x06 -> "telematic: national paging system"
            0x07 -> "telematic: Videotex"
            0x08 -> "telematic: teletex, carrier unspecified"
            0x09 -> "telematic: teletex, PSPDN"
            0x0A -> "telematic: teletex, CSPDN"
            0x0B -> "telematic: teletex, analog PSTN"
            0x0C -> "telematic: teletex, digital ISDN"
            0x0D -> "telematic: UCI"
            0x10 -> "telematic: message handling facility"
            0x11 -> "telematic: X.400 message handling system"
            0x12 -> "telematic: Internet electronic mail"
            in 0x18..0x1E -> "telematic: SC-specific"
            0x1F -> "telematic: GSM/UMTS mobile station"
            else -> "telematic: reserved"
        }

    /** TS 23.040 9.2.3.9: the 0x40..0x7F range is matched on the whole PID octet. */
    private fun shortMessagePid(pid: Int): String =
        when (pid) {
            0x40 -> "short message type 0"
            in 0x41..0x47 -> "replace short message type ${pid - 0x40}"
            0x48 -> "device triggering"
            0x5E -> "Enhanced Message Service (obsolete)"
            0x5F -> "return call"
            0x7C -> "ANSI-136 R-DATA"
            0x7D -> "ME data download"
            0x7E -> "ME de-personalization"
            0x7F -> "(U)SIM data download"
            else -> "reserved"
        }

    /** The reserved TP-PID values: the same set as `TpduParser.reservedPid` (AN-PID-RSV). */
    fun reservedPid(pid: Int): Boolean =
        (pid and 0xC0) == 0x80 ||
            pid == 0x2E || pid == 0x2F || pid in 0x33..0x37 ||
            pid in 0x49..0x5D || pid in 0x60..0x7B

    /**
     * TS 23.040 9.2.3.15: bits 6-5 give the class of the status. The class ranges agree with the
     * status thresholds of the Android framework (`Telephony.Sms.STATUS_PENDING` 32, `STATUS_FAILED` 64).
     */
    fun statusMeaning(status: Int): String =
        when (status) {
            0x00 -> "completed: received by the SME"
            0x01 -> "completed: forwarded, delivery not confirmed"
            0x02 -> "completed: replaced by the SC"
            in 0x03..0x0F -> "completed: reserved"
            in 0x10..0x1F -> "completed: SC-specific"
            in 0x20..0x3F -> "temporary error: SC still trying"
            in 0x40..0x5F -> "permanent error: SC stopped"
            in 0x60..0x7F -> "temporary error: SC stopped"
            else -> "reserved"
        }

    /** TS 23.040 9.2.3.19. */
    fun commandMeaning(commandType: Int): String =
        when (commandType) {
            0x00 -> "enquiry relating to a previously submitted short message"
            0x01 -> "cancel status report request"
            0x02 -> "delete previously submitted short message"
            0x03 -> "enable status report request"
            in 0x04..0x1F -> "reserved"
            in 0xE0..0xFF -> "SC-specific"
            else -> "unspecified"
        }
}
