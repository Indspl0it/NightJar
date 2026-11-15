package dev.nightjar.app.domain.ingest

import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.StringListColumn
import dev.nightjar.app.domain.detect.DetectContext
import dev.nightjar.app.domain.detect.SimOtaFacts
import dev.nightjar.app.domain.model.CbInfo
import dev.nightjar.app.domain.model.ConcatInfo
import dev.nightjar.app.domain.model.EmergencyCallState
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.model.EventFlags
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.FateDetail
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import dev.nightjar.contract.Wire
import dev.nightjar.pduparse.CbCategories
import org.json.JSONObject

/**
 * Reads one stored row back as the model type the classifiers take. Ingest and the severity reclassifier use this one
 * mapping, so a stored message gives the same facts in both.
 *
 * Ingest parsed the payload's JSON, so every column is already a contract wire name; an unknown one falls back to the
 * documented default instead of throwing. The rule name is not stored: the explainer names the rule by id.
 */
fun MessageEntity.toFacts(): EventFacts =
    EventFacts(
        eventId = eventId,
        direction = Wire.parse(Direction::class.java, direction, Direction.IN),
        transport = Wire.parse(Transport::class.java, transport, Transport.CS),
        source = source,
        classId = Wire.parse(ClassId::class.java, classId, ClassId.NORMAL),
        fate = trueFate(),
        receivedAtWallMs = receivedAtWall,
        revision = revision,
        sessionId = sessionId.orEmpty(),
        subId = subId,
        slot = slot,
        carrierName = carrierName,
        originator = originator,
        smsc = smsc,
        // Reads the raw destination (short code, premium pattern); old rows have only the normalised copy.
        destination = destination ?: destinationNorm,
        destPort = destPort,
        contentType = contentType,
        text = textBody,
        modemTsMs = modemTs,
        // A row from an older version has no env values: not roaming, and unlocked (the defaults of the payload).
        roaming = roaming == true,
        userUnlocked = userUnlocked != false,
        callingPackage = callingPackage,
        persistFlag = persistFlag,
        fateDetail = fateDetailMap(),
        verdict = Wire.parse(Verdict::class.java, verdict, Verdict.ALLOW),
        ruleId = ruleId,
        concat = concatRef?.let { ConcatInfo(it, partsTotal, partsHave) },
        cb = cbInfo(),
        flags = EventFlags(selfTest = isSelfTest, replay = isReplay, releaseOf = releaseOf, debug = debugInjected == true),
        buildFingerprint = buildFingerprint,
        payloadVersion = payloadVersion,
        // The classifier reads the anomalies for the severity floor, the titles read the subtype.
        subtype = subtype,
        extraSubtypes = StringListColumn.decode(extraSubtypes),
        anomalies = StringListColumn.decode(anomalies),
    )

/**
 * The detector context that the row itself can give: the SIM OTA header and the emergency call state that
 * the payload stored at capture. The row has no end time of an earlier emergency call, so the SUPL grace period after
 * a call applies only while the call is active. The OMA CP facts are not stored, so they stay "unknown".
 */
fun MessageEntity.detectContext(): DetectContext {
    val emergency = if (emergencyCallActive == true) EmergencyCallState(active = true) else EmergencyCallState.NONE
    return DetectContext(emergency = emergency, simOta = simOtaFacts())
}

/** A row stored by an older version can still say FT-UNKNOWN; read it as the true fate the row shows. */
private fun MessageEntity.trueFate(): Fate {
    val stored = Wire.parse(Fate::class.java, fate, Fate.UNKNOWN)
    if (stored != Fate.UNKNOWN) return stored
    return TrueFate.of(
        stored,
        Wire.parse(Direction::class.java, direction, Direction.IN),
        ackResult,
        replay = isReplay,
        noDispatch = fateDetailMap()[FateDetail.NO_DISPATCH] == "true",
    )
}

/** `fateDetail` JSON as string pairs, with the consumer package column as `package` when the JSON has none. */
private fun MessageEntity.fateDetailMap(): Map<String, String> {
    val parsed =
        fateDetail
            ?.let { json ->
                runCatching {
                    val obj = JSONObject(json)
                    buildMap {
                        for (key in obj.keys()) {
                            if (!obj.isNull(key)) put(key, obj.get(key).toString())
                        }
                    }
                }.getOrNull()
            }.orEmpty()
    val pkg = consumerPackage
    return if (pkg != null && EventFacts.FATE_DETAIL_PACKAGE !in parsed) parsed + (EventFacts.FATE_DETAIL_PACKAGE to pkg) else parsed
}

/** CB header facts of a broadcast row; the page counters are the folded pages of the row (CbIngest). */
private fun MessageEntity.cbInfo(): CbInfo? {
    val id = cbMessageId ?: return null
    return CbInfo(
        serial = cbSerial ?: -1,
        messageId = id,
        page = partsHave,
        pages = partsTotal,
        emergency = CbCategories.isEmergency(id),
    )
}

/** SPI and TAR of the stored SIM OTA header; null for no header or a response packet (no SPI). */
private fun MessageEntity.simOtaFacts(): SimOtaFacts? {
    val json = simOtaHeader ?: return null
    return runCatching {
        val obj = JSONObject(json)
        if (!obj.has(Ipc.SimOtaKey.SPI) || !obj.has(Ipc.SimOtaKey.TAR)) return null
        SimOtaFacts(spi = obj.getInt(Ipc.SimOtaKey.SPI), tar = obj.getInt(Ipc.SimOtaKey.TAR))
    }.getOrNull()
}
