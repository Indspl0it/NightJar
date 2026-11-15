package dev.nightjar.app.feature.protection

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.domain.protection.ModuleManager
import dev.nightjar.app.domain.protection.ProtectionAudit
import dev.nightjar.app.domain.protection.ProtectionAuditAction
import dev.nightjar.app.domain.protection.RootAccess
import javax.inject.Singleton

/** Binds the protection layer onto `RootOps` and the audit log. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProtectionBindings {
    @Binds
    @Singleton
    abstract fun rootAccess(impl: RootOpsAccess): RootAccess

    @Binds
    @Singleton
    abstract fun handshakeStore(impl: PrefsHandshakeStore): HandshakeStore

    companion object {
        /**
         * One instance for the whole app ([ModuleInstaller], [ProtectionMonitor] users and [PhoneWatchdog]), so the audit
         * mapping exists one time only.
         */
        @Provides
        @Singleton
        fun moduleManager(
            root: RootAccess,
            auditLog: AuditLog,
        ): ModuleManager =
            ModuleManager(
                root,
                ProtectionAudit { action: ProtectionAuditAction, target: String ->
                    auditLog.record(action.toAuditAction(), dev.nightjar.app.data.audit.AuditActor.USER, target)
                },
            )

        private fun ProtectionAuditAction.toAuditAction(): dev.nightjar.app.data.audit.AuditAction =
            when (this) {
                ProtectionAuditAction.PHONE_RESTART -> dev.nightjar.app.data.audit.AuditAction.PHONE_RESTART
                ProtectionAuditAction.REBOOT -> dev.nightjar.app.data.audit.AuditAction.PHONE_RESTART
            }
    }
}
