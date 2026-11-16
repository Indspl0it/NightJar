package dev.nightjar.app.core.ipc

import android.os.IBinder
import android.os.Parcel
import dev.nightjar.contract.Control
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App → payload control proxy.
 *
 * `dev.nightjar.IPayloadControl/1` is a hand-written binder interface: the payload writes the parcels, the app
 * has to match the exact code order and oneway flags. Everything is a [ControlResult] so a dead binder or a
 * `SecurityException` becomes a UI state instead of a crash; the UI thread never sees an exception.
 */
@Singleton
class PayloadConnection
    @Inject
    constructor(
        private val sessions: IpcSessionStore,
    ) {
        val isConnected: Boolean get() = sessions.session?.hasControl == true

        fun updateRules(json: String): ControlResult<Unit> = oneway(Control.Code.UPDATE_RULES) { it.writeString(json) }

        fun updateSettings(json: String): ControlResult<Unit> = oneway(Control.Code.UPDATE_SETTINGS) { it.writeString(json) }

        fun enableFirewall(enabled: Boolean): ControlResult<Unit> {
            val result = oneway(Control.Code.ENABLE_FIREWALL) { it.writeInt(if (enabled) 1 else 0) }
            if (result is ControlResult.Ok) sessions.setFirewallEnabled(enabled)
            return result
        }

        /** Control code `PING`: the payload's liveness value (`pingMs`), used for the payload liveness check. */
        fun ping(): ControlResult<Long> = call(Control.Code.PING, null) { reply -> reply.readLong() }

        fun release(
            eventId: String,
            pdus: List<ByteArray>,
            format: String,
            subId: Int,
        ): ControlResult<ReleaseResult> =
            call(
                Control.Code.RELEASE,
                { parcel ->
                    parcel.writeString(eventId)
                    parcel.writeInt(pdus.size)
                    for (pdu in pdus) parcel.writeByteArray(pdu)
                    parcel.writeString(format)
                    parcel.writeInt(subId)
                },
            ) { reply -> ReleaseCodes.map(reply.readInt()) }

        fun runSelfTest(classesJson: String): ControlResult<String> =
            call(Control.Code.RUN_SELF_TEST, { it.writeString(classesJson) }) { reply -> reply.readString().orEmpty() }

        fun cancelSelfTest(runId: String): ControlResult<Unit> = oneway(Control.Code.CANCEL_SELF_TEST) { it.writeString(runId) }

        fun getHookHealth(): ControlResult<String> = call(Control.Code.GET_HOOK_HEALTH, null) { reply -> reply.readString().orEmpty() }

        private fun <T> call(
            code: Int,
            write: ((Parcel) -> Unit)?,
            read: (Parcel) -> T,
        ): ControlResult<T> {
            val binder = requireBinder() ?: return noBinderResult()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(Control.DESCRIPTOR)
                write?.invoke(data)
                val handled = binder.transact(code, data, reply, 0)
                if (!handled) {
                    ControlResult.Failed(NOT_HANDLED)
                } else {
                    reply.readException()
                    ControlResult.Ok(read(reply))
                }
            } catch (_: android.os.DeadObjectException) {
                // The binder is gone; ControlResult.Dead is the typed outcome and the cause adds nothing the UI can use.
                sessions.clearIfControl(binder)
                ControlResult.Dead
            } catch (e: SecurityException) {
                ControlResult.Failed("rejected by payload: ${e.message}")
            } catch (e: android.os.RemoteException) {
                ControlResult.Failed(e.message ?: "remote failure")
            } catch (e: RuntimeException) {
                ControlResult.Failed(e.message ?: "parcel failure")
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        /** Oneway calls have no reply; the parcel is freed as soon as `transact` returns. */
        private fun oneway(
            code: Int,
            write: (Parcel) -> Unit,
        ): ControlResult<Unit> {
            val binder = requireBinder() ?: return noBinderResult()
            val data = Parcel.obtain()
            return try {
                data.writeInterfaceToken(Control.DESCRIPTOR)
                write(data)
                binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
                ControlResult.Ok(Unit)
            } catch (_: android.os.DeadObjectException) {
                // The binder is gone; ControlResult.Dead is the typed outcome and the cause adds nothing the UI can use.
                sessions.clearIfControl(binder)
                ControlResult.Dead
            } catch (e: SecurityException) {
                ControlResult.Failed("rejected by payload: ${e.message}")
            } catch (e: android.os.RemoteException) {
                // TransactionTooLargeException and other checked remote failures must not stop the sync collector.
                ControlResult.Failed(e.message ?: "remote failure")
            } catch (e: RuntimeException) {
                ControlResult.Failed(e.message ?: "parcel failure")
            } finally {
                data.recycle()
            }
        }

        /** Null means "no live binder"; [noBinderResult] maps it to [ControlResult.NoSession] or [ControlResult.Dead]. */
        private fun requireBinder(): IBinder? {
            val binder = sessions.session?.control ?: return null
            return binder.takeIf { it.isBinderAlive }
        }

        /** A session whose binder died is reported as [ControlResult.Dead] (and cleared); no session is NoSession. */
        private fun <T> noBinderResult(): ControlResult<T> {
            val session = sessions.session ?: return ControlResult.NoSession
            session.control?.let(sessions::clearIfControl)
            return if (session.control != null) ControlResult.Dead else ControlResult.NoSession
        }

        companion object {
            /** The [ControlResult.Failed] message when the payload does not handle the code (`transact` gave false). */
            const val NOT_HANDLED = "transact returned false"
        }
    }
