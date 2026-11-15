package dev.nightjar.app.data.repository

import androidx.room.withTransaction
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.db.ClassActionDao
import dev.nightjar.app.data.db.ClassActionEntity
import dev.nightjar.app.data.db.KnownOriginatorDao
import dev.nightjar.app.data.db.KnownOriginatorEntity
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.RuleDao
import dev.nightjar.app.data.db.RuleEntity
import dev.nightjar.app.data.db.RuleMetaDao
import dev.nightjar.app.data.db.RuleMetaEntity
import dev.nightjar.app.data.model.Rule
import dev.nightjar.app.data.model.toModel
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Schemas
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Presets
import dev.nightjar.rules.RuleEngine
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.RulesConfig
import dev.nightjar.rules.RulesFormatException
import dev.nightjar.rules.RulesJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import dev.nightjar.rules.Rule as EngineRule

/**
 * Rules storage (`rule`, `class_action`, `known_originator`).
 *
 * The database is the source of truth; [snapshot] rebuilds the `:rules` [RulesConfig] that the payload receives and
 * that `rules.json` is written from, so the app and the payload always evaluate the same document. Every mutation bumps
 * the snapshot revision, audits itself, and returns the new config for `RulesSync` to push.
 */
interface RulesRepository {
    /** The full document as the payload sees it. Emits on every rule, class-action, known-originator or preset change. */
    fun observe(): Flow<RulesConfig>

    suspend fun snapshot(): RulesConfig

    /**
     * Applies a preset: replaces class actions and preset rules, keeps user rules. [actor] goes into the
     * audit entry.
     */
    suspend fun applyPreset(
        preset: Preset,
        actor: AuditActor = AuditActor.USER,
    ): RulesConfig

    /**
     * Applies [Presets.DEFAULT_PRESET] when no preset was ever stored, and returns the new document. Returns
     * null and changes nothing when a preset is stored, so an existing install keeps its preset.
     */
    suspend fun applyDefaultPresetOnce(): RulesConfig?

    /**
     * The preset that is on after onboarding ends: the stored preset, or [Presets.DEFAULT_PRESET] when no
     * preset was ever stored ([applyDefaultPresetOnce] applies it when onboarding ends). Changes nothing.
     */
    suspend fun presetAfterSetup(): Preset

    suspend fun setClassAction(
        classId: ClassId,
        verdict: Verdict,
    ): RulesConfig

    /** Creates a user rule and returns the stored form (with its assigned id and priority). */
    suspend fun createRule(
        name: String,
        action: Verdict,
        whenConditions: Conditions,
        enabled: Boolean = true,
    ): Pair<Rule, RulesConfig>

    suspend fun updateRule(
        id: Long,
        name: String,
        action: Verdict,
        whenConditions: Conditions,
        enabled: Boolean,
    ): RulesConfig?

    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ): RulesConfig?

    /** Rewrites priorities to match [orderedIds] (04 drag order) and audits `RULE_REORDER`. */
    suspend fun reorder(orderedIds: List<Long>): RulesConfig

    suspend fun deleteRule(id: Long): RulesConfig?

    suspend fun incrementMatch(
        ruleId: Long,
        atWallMs: Long,
    )

    /** Replaces the whole document, e.g. a rules import or the first-run default. */
    suspend fun replace(config: RulesConfig): RulesConfig

    /** `rules.json` body for the module file and the control binder, refusing documents over 64 KB. */
    suspend fun serialize(): String

    fun observeRules(): Flow<List<Rule>>

    fun observeClassActions(): Flow<Map<ClassId, Verdict>>
}

@Singleton
class RoomRulesRepository
    @Inject
    constructor(
        private val db: NightjarDatabase,
        private val rules: RuleDao,
        private val classActions: ClassActionDao,
        private val knownOriginators: KnownOriginatorDao,
        private val meta: RuleMetaDao,
        private val settings: SettingsStore,
        private val audit: AuditLog,
        private val clock: Clock,
    ) : RulesRepository {
        // The revision and preset (rule_meta) and the SUPL grace setting are part of the document,
        // so a change to any of them emits again; the grace change then reaches the payload.
        override fun observe(): Flow<RulesConfig> =
            combine(
                rules.observeAll(),
                classActions.observeAll(),
                knownOriginators.observeAll(),
                meta.observeAll(),
                settings.settings.map { it.detectSuplGraceMin }.distinctUntilChanged(),
            ) { r, ca, ko, _, grace ->
                build(r, ca, ko, grace)
            }

        override fun observeRules(): Flow<List<Rule>> = rules.observeAll().map { rows -> rows.map { it.toModel() } }

        override fun observeClassActions(): Flow<Map<ClassId, Verdict>> =
            classActions.observeAll().map { rows -> rows.associate { toClassId(it.classId) to toVerdict(it.verdict) } }

        override suspend fun snapshot(): RulesConfig =
            build(rules.all(), classActions.all(), knownOriginators.all(), settings.settings.value.detectSuplGraceMin)

        override suspend fun applyDefaultPresetOnce(): RulesConfig? {
            if (meta.get(RuleMetaEntity.KEY_PRESET) != null) return null
            return applyPreset(Presets.DEFAULT_PRESET, AuditActor.SYSTEM)
        }

        override suspend fun presetAfterSetup(): Preset {
            val stored = meta.get(RuleMetaEntity.KEY_PRESET) ?: return Presets.DEFAULT_PRESET
            // The same fallback as build(): an unknown stored value reads as Monitor.
            return Preset.entries.firstOrNull { it.wire() == stored } ?: Preset.MONITOR
        }

        override suspend fun applyPreset(
            preset: Preset,
            actor: AuditActor,
        ): RulesConfig {
            val before = snapshot()
            val applied = Presets.apply(preset, before)
            val now = clock.now()
            // One transaction, and preset rows are inserted with ABORT. A crash between the delete and the
            // inserts used to leave the device with no preset rules at all, and a REPLACE insert would silently
            // overwrite a user rule that happened to hold the same id — the state would be inconsistent but "saved".
            db.withTransaction {
                rules.deleteByOrigin(RuleOrigin.PRESET.wire())
                applied.rules
                    .filter { it.origin == RuleOrigin.PRESET }
                    .forEach { rule -> rules.insert(rule.toEntity(now)) }
                // A preset replaces every class action. writeClassActions only upserts the non-ALLOW ones, so
                // without this delete a class held by Strict or set by the user stayed after a change to Monitor.
                classActions.deleteAll()
                writeClassActions(applied.classActions, origin = "PRESET")
                putMeta(RuleMetaEntity.KEY_PRESET, preset.wire())
                bumpRevision()
            }
            applyNotifyNotable(before.preset, preset)
            val after = snapshot()
            audit.record(
                action = AuditAction.PRESET_CHANGE,
                actor = actor,
                target = preset.wire(),
                before = jsonOf("preset" to before.preset.wire(), "classActions" to before.classActions.size),
                after = jsonOf("preset" to preset.wire(), "classActions" to applied.classActions.size),
            )
            return after
        }

        override suspend fun setClassAction(
            classId: ClassId,
            verdict: Verdict,
        ): RulesConfig {
            val before = snapshot().classAction(classId)
            val after =
                db.withTransaction {
                    classActions.upsertAll(listOf(ClassActionEntity(classId.wire(), verdict.wire(), "USER")))
                    putMeta(RuleMetaEntity.KEY_PRESET, Preset.CUSTOM.wire())
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.CLASS_ACTION_CHANGE,
                actor = AuditActor.USER,
                target = classId.wire(),
                before = AuditLog.scalar(before.wire()),
                after = AuditLog.scalar(verdict.wire()),
            )
            return after
        }

        override suspend fun createRule(
            name: String,
            action: Verdict,
            whenConditions: Conditions,
            enabled: Boolean,
        ): Pair<Rule, RulesConfig> {
            // User ids stay below the reserved preset range, so a preset can never overwrite a user rule (and a
            // user rule can never take a preset's id) whatever order they were created in.
            val all = rules.all()
            val id = nextUserId(all)
            val priority = (all.maxOfOrNull { it.priority } ?: 0) + 1
            val now = clock.now()
            val entity =
                RuleEntity(
                    id = id,
                    priority = priority,
                    enabled = enabled,
                    name = name,
                    conditionsJson = serializeConditions(whenConditions),
                    action = action.wire(),
                    origin = RuleOrigin.USER.wire(),
                    createdAt = now,
                    updatedAt = now,
                    matchCount = 0,
                    lastMatchAt = null,
                )
            val config =
                db.withTransaction {
                    rules.insert(entity)
                    markCustom()
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.RULE_CREATE,
                actor = AuditActor.USER,
                target = "rule:$id",
                after = ruleJson(entity).toString(),
            )
            return entity.toModel() to config
        }

        override suspend fun updateRule(
            id: Long,
            name: String,
            action: Verdict,
            whenConditions: Conditions,
            enabled: Boolean,
        ): RulesConfig? {
            val existing = rules.find(id) ?: return null
            val updated =
                existing.copy(
                    name = name,
                    action = action.wire(),
                    conditionsJson = serializeConditions(whenConditions),
                    enabled = enabled,
                    updatedAt = clock.now(),
                )
            val config =
                db.withTransaction {
                    rules.update(updated)
                    markCustom()
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.RULE_UPDATE,
                actor = AuditActor.USER,
                target = "rule:$id",
                before = ruleJson(existing).toString(),
                after = ruleJson(updated).toString(),
            )
            return config
        }

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ): RulesConfig? {
            val existing = rules.find(id) ?: return null
            if (existing.enabled == enabled) return snapshot()
            val config =
                db.withTransaction {
                    rules.setEnabled(id, enabled)
                    markCustom()
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.RULE_UPDATE,
                actor = AuditActor.USER,
                target = "rule:$id",
                before = jsonOf("enabled" to existing.enabled),
                after = jsonOf("enabled" to enabled),
            )
            return config
        }

        /**
         * Only USER rules move. The engine runs USER rules before preset rules, and a preset row keeps its
         * priority. The user rules in [orderedIds] take the priority slots that they already hold, in the new order,
         * so no user rule gets the priority of a preset rule. An id that is not a user rule is ignored.
         */
        override suspend fun reorder(orderedIds: List<Long>): RulesConfig {
            val before = rules.all().associateBy { it.id }
            val userIds = orderedIds.distinct().filter { before[it]?.origin == RuleOrigin.USER.wire() }
            val slots = userIds.mapNotNull { before[it]?.priority }.sorted()
            val config =
                db.withTransaction {
                    userIds.forEachIndexed { index, id -> rules.setPriority(id, slots[index]) }
                    markCustom()
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.RULE_REORDER,
                actor = AuditActor.USER,
                target = userIds.joinToString(",") { "rule:$it" },
                before = jsonOf("order" to userIds.mapNotNull { before[it]?.priority }),
                after = jsonOf("order" to slots),
            )
            return config
        }

        override suspend fun deleteRule(id: Long): RulesConfig? {
            val existing = rules.find(id) ?: return null
            val config =
                db.withTransaction {
                    rules.delete(id)
                    markCustom()
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.RULE_DELETE,
                actor = AuditActor.USER,
                target = "rule:$id",
                before = ruleJson(existing).toString(),
            )
            return config
        }

        override suspend fun incrementMatch(
            ruleId: Long,
            atWallMs: Long,
        ) {
            rules.incrementMatch(ruleId, atWallMs)
        }

        override suspend fun replace(config: RulesConfig): RulesConfig {
            val before = snapshot()
            val now = clock.now()
            val rows = config.rules.map { rule -> rule.toEntity(now) }
            // One transaction, so a crash cannot leave half a rule set.
            val after =
                db.withTransaction {
                    rules.deleteAll()
                    classActions.deleteAll()
                    if (rows.isNotEmpty()) rules.upsertAll(rows)
                    writeClassActions(config.classActions, origin = if (config.preset == Preset.CUSTOM) "USER" else "PRESET")
                    putMeta(RuleMetaEntity.KEY_PRESET, config.preset.wire())
                    bumpRevision()
                }
            audit.record(
                action = AuditAction.RULE_UPDATE,
                actor = AuditActor.USER,
                target = "rules:all",
                before = jsonOf(Ipc.Key.RULES to before.rules.size, "preset" to before.preset.wire()),
                after = jsonOf(Ipc.Key.RULES to config.rules.size, "preset" to config.preset.wire()),
            )
            return after
        }

        override suspend fun serialize(): String = RulesJson.serializeChecked(snapshot())

        // ------------------------------------------------------------------ internals

        private suspend fun build(
            ruleRows: List<RuleEntity>,
            actionRows: List<ClassActionEntity>,
            knownRows: List<KnownOriginatorEntity>,
            suplGraceMin: Int,
        ): RulesConfig {
            val preset = Preset.entries.firstOrNull { it.wire() == meta.get(RuleMetaEntity.KEY_PRESET) } ?: Preset.MONITOR
            val revision = meta.get(RuleMetaEntity.KEY_REVISION)?.toLongOrNull() ?: 0L
            val actions = LinkedHashMap<ClassId, Verdict>()
            actionRows.forEach { actions[toClassId(it.classId)] = toVerdict(it.verdict) }
            val engineRules: List<EngineRule> = ruleRows.map { it.toRule() }
            val exact = knownRows.filter { it.kind == "EXACT" }.map { it.value }
            val prefix = knownRows.filter { it.kind == "PREFIX" }.map { it.value }
            return RulesConfig(
                Schemas.RULES,
                revision,
                preset,
                actions,
                engineRules,
                exact,
                prefix,
                suplGraceMin,
            )
        }

        /**
         * The next user rule id below the preset range. When `max + 1` would reach the range, the smallest
         * free id is taken; when none is left, the create fails with a clear error and writes nothing.
         */
        private fun nextUserId(all: List<RuleEntity>): Long {
            val max = all.filter { it.id < Presets.PRESET_ID_BASE }.maxOfOrNull { it.id } ?: 0L
            if (max + 1 < Presets.PRESET_ID_BASE) return (max + 1).coerceAtLeast(1L)
            val used = all.mapTo(HashSet()) { it.id }
            var candidate = 1L
            while (candidate < Presets.PRESET_ID_BASE) {
                if (candidate !in used) return candidate
                candidate++
            }
            error("No free user rule id below the preset range")
        }

        /**
         * A preset that turns on notify.notable (Strict) saves the user's value first; a
         * change to a preset that does not turn it on restores the saved value.
         */
        private suspend fun applyNotifyNotable(
            from: Preset,
            to: Preset,
        ) {
            val wanted = Presets.notifyNotable(to)
            val had = Presets.notifyNotable(from)
            if (wanted && !had) {
                putMeta(KEY_NOTABLE_BEFORE_PRESET, settings.current().notifyNotable.toString())
                settings.setNotifyNotable(true)
            } else if (!wanted && had) {
                val saved = meta.get(KEY_NOTABLE_BEFORE_PRESET)?.toBooleanStrictOrNull() ?: return
                putMeta(KEY_NOTABLE_BEFORE_PRESET, "")
                settings.setNotifyNotable(saved)
            }
        }

        /** Reads the new revision and returns the rebuilt document. */
        private suspend fun bumpRevision(): RulesConfig {
            val next = (meta.get(RuleMetaEntity.KEY_REVISION)?.toLongOrNull() ?: 0L) + 1
            putMeta(RuleMetaEntity.KEY_REVISION, next.toString())
            return snapshot()
        }

        private suspend fun putMeta(
            key: String,
            value: String,
        ) = meta.put(RuleMetaEntity(key, value))

        /** Any user-level change leaves a preset behind: the actions are no longer the preset's (AC2). */
        private suspend fun markCustom() {
            val current = meta.get(RuleMetaEntity.KEY_PRESET)
            if (current != Preset.CUSTOM.wire()) putMeta(RuleMetaEntity.KEY_PRESET, Preset.CUSTOM.wire())
        }

        private suspend fun writeClassActions(
            actions: Map<ClassId, Verdict>,
            origin: String,
        ) {
            // ALLOW is the absent action of most classes, so it is not stored. CL-UNREADABLE-SMS is held
            // when it is absent, so its ALLOW row stays (RulesConfig.absentClassAction).
            val stored =
                actions.filter { (classId, verdict) ->
                    verdict != Verdict.ALLOW || RulesConfig.absentClassAction(classId, Preset.CUSTOM) != Verdict.ALLOW
                }
            classActions.upsertAll(
                stored.map { (classId, verdict) -> ClassActionEntity(classId.wire(), verdict.wire(), origin) },
            )
        }

        /** The stored form of an engine rule; `matchCount` starts at zero because the row is new. */
        private fun EngineRule.toEntity(now: Long): RuleEntity =
            RuleEntity(
                id = id,
                priority = priority,
                enabled = enabled,
                name = name,
                conditionsJson = serializeConditions(`when`()),
                action = action.wire(),
                origin = origin.wire(),
                createdAt = now,
                updatedAt = now,
                matchCount = 0,
                lastMatchAt = null,
            )

        private fun RuleEntity.toRule(): EngineRule {
            val parsed = parseConditions(conditionsJson)
            // Conditions that no longer parse must not become "match everything": with a BLOCK action that rule would
            // quarantine or block every message. The rule stays listed, but switched off.
            val conditions = parsed ?: Conditions.ANY
            return EngineRule(
                id,
                priority,
                enabled && parsed != null,
                if (origin == RuleOrigin.PRESET.wire()) RuleOrigin.PRESET else RuleOrigin.USER,
                name,
                toVerdict(action),
                conditions,
            )
        }

        /** Serialises one `when` block through :rules, so the stored form is exactly what the payload parses. */
        private fun serializeConditions(conditions: Conditions): String {
            val doc =
                RulesJson.toJson(
                    RulesConfig.DEFAULT.withRules(
                        listOf(EngineRule(0, 0, true, RuleOrigin.USER, "", Verdict.ALLOW, conditions)),
                    ),
                )
            return doc
                .getJSONArray(Ipc.Key.RULES)
                .getJSONObject(0)
                .getJSONObject(RulesJson.KEY_WHEN)
                .toString()
        }

        /**
         * Parses a stored `when` block by round-tripping it through a one-rule document. the document is
         * written by `:rules` itself, and the `when` key is `RulesJson.KEY_WHEN`.
         */
        private fun parseConditions(json: String): Conditions? {
            val whenBlock = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val doc =
                RulesJson.toJson(
                    RulesConfig.DEFAULT.withRules(
                        listOf(EngineRule(0, 0, true, RuleOrigin.USER, "", Verdict.ALLOW, Conditions.ANY)),
                    ),
                )
            doc.getJSONArray(Ipc.Key.RULES).getJSONObject(0).put(RulesJson.KEY_WHEN, whenBlock)
            return try {
                val parsed = RulesJson.parse(doc.toString()).rules.first()
                // The reader keeps an unreadable `when` as a partial one and flags the rule instead of throwing; taking
                // the partial block would widen the rule.
                if (parsed.unsupported() != null) null else parsed.`when`()
            } catch (_: RulesFormatException) {
                null
            }
        }

        private fun ruleJson(entity: RuleEntity): JSONObject =
            JSONObject()
                .put("id", entity.id)
                .put("priority", entity.priority)
                .put("enabled", entity.enabled)
                .put("name", entity.name)
                .put("action", entity.action)
                .put("origin", entity.origin)
                .put(RulesJson.KEY_WHEN, runCatching { JSONObject(entity.conditionsJson) }.getOrElse { JSONObject() })

        private companion object {
            /** rule_meta key: the notify.notable value from before a preset turned it on; empty when none. */
            const val KEY_NOTABLE_BEFORE_PRESET = "notifyNotableBeforePreset"
        }
    }

/** Compiles the document once so callers can preview a rule's effect. */
fun RulesConfig.compile(): RuleEngine = RuleEngine.compile(this)

// Pure helpers of RoomRulesRepository, outside the class (detekt TooManyFunctions).

private fun toClassId(wire: String): ClassId = ClassId.entries.firstOrNull { it.wire() == wire } ?: ClassId.NORMAL

private fun toVerdict(wire: String): Verdict = Verdict.entries.firstOrNull { it.wire() == wire } ?: Verdict.ALLOW

private fun jsonOf(vararg pairs: Pair<String, Any?>): String {
    val o = JSONObject()
    pairs.forEach { (k, v) -> o.put(k, v) }
    return o.toString()
}
