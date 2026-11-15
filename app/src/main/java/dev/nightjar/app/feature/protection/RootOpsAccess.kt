package dev.nightjar.app.feature.protection

import dev.nightjar.app.core.root.RootOps
import dev.nightjar.app.core.root.ShellResult
import dev.nightjar.app.domain.protection.RootAccess
import dev.nightjar.app.domain.protection.RootManager
import dev.nightjar.app.domain.protection.RootResult
import dev.nightjar.app.domain.protection.RootSolution
import javax.inject.Inject
import javax.inject.Singleton
import dev.nightjar.app.core.root.RootSolution as CoreRootSolution

/**
 * The only adapter between the protection layer and [RootOps].
 *
 * Every root command in the app goes through `RootOps`; this class only maps types. It runs no shell command of its
 * own, which is what makes a review of root use a one-line grep (`Shell.cmd` appears only inside `LibsuShell`).
 */
@Singleton
class RootOpsAccess
    @Inject
    constructor(
        private val root: RootOps,
    ) : RootAccess {
        override suspend fun detectRoot(): RootSolution = root.detectRootSolution().toDomain()

        override suspend fun detectXposed(): Boolean = root.detectXposed()

        override suspend fun restartPhoneProcess(): RootResult = root.restartPhoneProcess().toDomain()

        override suspend fun reboot(): RootResult = root.reboot().toDomain()

        private fun ShellResult.toDomain(): RootResult = RootResult(exitCode, stdout)

        private fun CoreRootSolution.toDomain(): RootSolution =
            RootSolution(
                manager =
                    when (manager) {
                        dev.nightjar.app.core.root.RootManager.MAGISK -> RootManager.MAGISK
                        dev.nightjar.app.core.root.RootManager.KERNEL_SU -> RootManager.KERNELSU
                        dev.nightjar.app.core.root.RootManager.APATCH -> RootManager.APATCH
                        dev.nightjar.app.core.root.RootManager.UNKNOWN -> RootManager.UNKNOWN
                    },
                version = version,
                rooted = rooted,
                diagnostic = diagnostic,
            )
    }
