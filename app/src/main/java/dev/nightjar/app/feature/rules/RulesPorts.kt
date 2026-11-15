package dev.nightjar.app.feature.rules

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.model.MessageDetail
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.Rule
import dev.nightjar.rules.RuleValidator
import dev.nightjar.rules.RulesConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import dev.nightjar.app.data.model.Rule as StoredRule

/** What Custom rules needs. */
interface CustomRulesPort {
    val config: Flow<RulesConfig>

    /** Rows carry the stored match count and last-match time, which the engine snapshot does not keep. */
    val storedRules: Flow<List<StoredRule>>

    /** `privacy.maskNumbers`, for the sender value in a rule summary. */
    val maskNumbers: Flow<Boolean>

    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    )

    suspend fun reorder(orderedIds: List<Long>)

    suspend fun delete(id: Long)

    suspend fun create(
        name: String,
        action: Verdict,
        conditions: Conditions,
        enabled: Boolean,
    ): Long
}

/** What Rule editor needs. */
interface RuleEditorPort {
    suspend fun snapshot(): RulesConfig

    suspend fun rule(id: Long): Rule?

    suspend fun message(id: Long): MessageDetail?

    suspend fun create(
        name: String,
        action: Verdict,
        conditions: Conditions,
        enabled: Boolean,
    ): Long

    suspend fun update(
        id: Long,
        name: String,
        action: Verdict,
        conditions: Conditions,
        enabled: Boolean,
    ): RulesConfig?

    /**
     * True when the rule can be saved without pushing the document over 64 KB.
     *
     * The check runs on a projected snapshot **before** the write, so a too-large document never reaches the database.
     */
    suspend fun fitsBudget(rule: Rule): Boolean

    /** Validates the persisted document; throws `RulesFormatException` when it exceeds the limit. */
    suspend fun serialize(): String

    /** Typed problems for the whole stored document (e.g. a corrupt import). */
    suspend fun validateStored(): List<RuleValidator.Error>
}

@Singleton
class RepositoryRulesPort
    @Inject
    constructor(
        private val rules: RulesRepository,
        private val messages: MessageRepository,
        private val settings: SettingsStore,
    ) : CustomRulesPort,
        RuleEditorPort {
        override val config: Flow<RulesConfig> = rules.observe()

        override val storedRules: Flow<List<StoredRule>> = rules.observeRules()

        override val maskNumbers: Flow<Boolean> = settings.settings.map { it.maskNumbers }.distinctUntilChanged()

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            rules.setEnabled(id, enabled)
        }

        override suspend fun reorder(orderedIds: List<Long>) {
            rules.reorder(orderedIds)
        }

        override suspend fun delete(id: Long) {
            rules.deleteRule(id)
        }

        override suspend fun snapshot(): RulesConfig = rules.snapshot()

        override suspend fun rule(id: Long): Rule? = rules.snapshot().rules.firstOrNull { it.id() == id }

        override suspend fun message(id: Long): MessageDetail? = messages.find(id)

        override suspend fun create(
            name: String,
            action: Verdict,
            conditions: Conditions,
            enabled: Boolean,
        ): Long = rules.createRule(name, action, conditions, enabled).first.id

        override suspend fun update(
            id: Long,
            name: String,
            action: Verdict,
            conditions: Conditions,
            enabled: Boolean,
        ): RulesConfig? = rules.updateRule(id, name, action, conditions, enabled)

        override suspend fun fitsBudget(rule: Rule): Boolean {
            val base = rules.snapshot()
            val projected = base.withRules(base.rules.filter { it.id() != rule.id() } + rule)
            return dev.nightjar.rules.RulesJson
                .utf8Length(
                    dev.nightjar.rules.RulesJson
                        .serialize(projected),
                ) <=
                dev.nightjar.rules.RulesJson.MAX_BYTES
        }

        override suspend fun serialize(): String = rules.serialize()

        override suspend fun validateStored(): List<RuleValidator.Error> = RuleValidator.validate(rules.snapshot())
    }

@Module
@InstallIn(SingletonComponent::class)
object RulesBindings {
    @Provides
    @Singleton
    fun customRulesPort(impl: RepositoryRulesPort): CustomRulesPort = impl

    @Provides
    @Singleton
    fun ruleEditorPort(impl: RepositoryRulesPort): RuleEditorPort = impl
}
