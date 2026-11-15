package dev.nightjar.payload.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import dev.nightjar.contract.Control;

/**
 * Hand-written {@code IPayloadControl}. Marshals parcels and delegates every decision to
 * {@link ControlHandler}; the caller check happens before any argument is read.
 *
 * <p>Device only — the JVM tests cover {@link ControlHandler} and {@link CallerGate}.
 *
 * <p>Every synchronous reply starts with {@link Parcel#writeNoException()}: the app reads it with
 * {@code Parcel.readException()}, as an AIDL-generated proxy does. Without the header the first result int is read as an
 * exception code.
 *
 * <p>Thread: binder threads. {@code release}, {@code replay}, {@code runSelfTest}, {@code getHookHealth},
 * {@code getLog} and {@code ping} are synchronous; the others are oneway.
 */
public final class ControlBinder extends Binder {

    private final ControlHandler handler;
    private final CallerGate gate;

    public ControlBinder(ControlHandler handler, CallerGate gate) {
        this.handler = handler;
        this.gate = gate;
    }

    /** The binder to hand to the app in {@code hello}. */
    public IBinder asControlBinder() {
        return this;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(Control.DESCRIPTOR);
            return true;
        }
        boolean oneway = isOneway(code);
        if (!gate.allowed(Binder.getCallingUid())) {
            if (!oneway && reply != null) writeRejection(code, reply);
            return true;
        }
        if (data != null) data.enforceInterface(Control.DESCRIPTOR);
        switch (code) {
            case Control.Code.UPDATE_RULES:
                handler.updateRules(data == null ? null : data.readString());
                return true;
            case Control.Code.UPDATE_SETTINGS:
                handler.updateSettings(data == null ? null : data.readString());
                return true;
            case Control.Code.SET_SAFE_MODE:
                handler.setSafeMode(data != null && data.readInt() != 0);
                return true;
            case Control.Code.ENABLE_FIREWALL:
                handler.enableFirewall(data != null && data.readInt() != 0);
                return true;
            case Control.Code.RELEASE:
                release(data, reply, false);
                return true;
            case Control.Code.REPLAY:
                release(data, reply, true);
                return true;
            case Control.Code.RUN_SELF_TEST:
                if (reply != null) {
                    String result = handler.runSelfTest(data == null ? null : data.readString());
                    reply.writeNoException();
                    reply.writeString(result);
                }
                return true;
            case Control.Code.GET_HOOK_HEALTH:
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeString(handler.hookHealthJson());
                }
                return true;
            case Control.Code.GET_LOG:
                if (reply != null) {
                    String lines = handler.logJson(data == null ? 0L : data.readLong());
                    reply.writeNoException();
                    reply.writeString(lines);
                }
                return true;
            case Control.Code.PING:
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeLong(handler.pingMs());
                }
                return true;
            case Control.Code.CANCEL_SELF_TEST:
                handler.cancelSelfTest(data == null ? null : data.readString());
                return true;
            default:
                return false;
        }
    }

    private void release(Parcel data, Parcel reply, boolean replay) {
        if (data == null) {
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(Control.ReleaseResult.INVALID_ARGUMENTS);
            }
            return;
        }
        String eventId = data.readString();
        int partCount = data.readInt();
        if (partCount <= 0 || partCount > 32) {
            // The byte arrays cannot be skipped without reading them: the fields after them would be misread.
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(Control.ReleaseResult.INVALID_ARGUMENTS);
            }
            return;
        }
        byte[][] pdus = new byte[partCount][];
        for (int i = 0; i < partCount; i++) pdus[i] = data.createByteArray();
        String format = data.readString();
        int subId = data.readInt();
        int result = replay
                ? handler.replay(eventId, pdus, format, subId)
                : handler.release(eventId, pdus, format, subId);
        if (reply != null) {
            reply.writeNoException();
            reply.writeInt(result);
        }
    }

    private static void writeRejection(int code, Parcel reply) {
        // The app reads every synchronous reply with Parcel.readException(), as an AIDL proxy does.
        reply.writeNoException();
        switch (code) {
            case Control.Code.RELEASE:
            case Control.Code.REPLAY:
                reply.writeInt(Control.ReleaseResult.CALLER_REJECTED);
                break;
            case Control.Code.RUN_SELF_TEST:
                reply.writeString(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID);
                break;
            case Control.Code.GET_HOOK_HEALTH:
                reply.writeString("{}");
                break;
            case Control.Code.GET_LOG:
                reply.writeString("[]");
                break;
            case Control.Code.PING:
                reply.writeLong(0L);
                break;
            default:
                break;
        }
    }

    /** Oneway control codes (shared table with {@link ControlHandler}). */
    public static boolean isOneway(int code) {
        return ControlHandler.isOneway(code);
    }
}
