package dev.nightjar.app.core.ipc

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.nightjar.app.feature.classify.QuarantineReleaser
import javax.inject.Singleton

/**
 * Bindings for the payload IPC and the release path.
 *
 * The staging database is not bound here: [dev.nightjar.app.data.staging.StagingService] owns it, opens it on the
 * device-protected context with its own key on first use, and reports a failure as `false` instead of throwing
 * out of a provider that runs in Direct Boot.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class IpcBindings {
    @Binds
    @Singleton
    @IntoSet
    abstract fun quarantineReleaser(impl: ReleaseGateway): QuarantineReleaser

    @Binds
    @Singleton
    abstract fun stagingSink(impl: dev.nightjar.app.data.staging.StagingService): dev.nightjar.app.data.staging.StagingSink
}
