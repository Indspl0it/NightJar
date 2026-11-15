package dev.nightjar.payload.ipc;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link IpcTransport} over {@code ContentResolver.call}.
 *
 * <p>Failure mapping: an unknown authority or a disabled/force-stopped app surfaces as
 * {@link IpcException.Kind#APP_ABSENT}; a {@code null} reply is a rejected caller; a dead binder is
 * {@link IpcException.Kind#DEAD_OBJECT}. Everything else is {@link IpcException.Kind#FAILURE}.
 *
 * <p>Device only — the worker is tested against a fake transport.
 *
 * <p>Thread: IPC worker.
 */
public final class ContentResolverTransport implements IpcTransport {

    private final Context context;

    public ContentResolverTransport(Context context) {
        this.context = context;
    }

    @Override
    public IpcReply call(IpcRequest request) throws IpcException {
        ContentResolver resolver = context == null ? null : context.getContentResolver();
        if (resolver == null) throw new IpcException(IpcException.Kind.APP_ABSENT, "no content resolver");
        Bundle extras = toBundle(request);
        Bundle reply;
        try {
            reply = resolver.call(Uri.parse("content://" + request.authority()), request.method(), null, extras);
        } catch (IllegalArgumentException e) {
            throw new IpcException(IpcException.Kind.APP_ABSENT, "provider unavailable", e);
        } catch (SecurityException e) {
            throw new IpcException(IpcException.Kind.REJECTED, "provider denied the call", e);
        } catch (Throwable t) {
            throw new IpcException(kindOf(t), "call failed", t);
        }
        if (reply == null) throw new IpcException(IpcException.Kind.REJECTED, "null reply");
        return new IpcReply(toMap(reply));
    }

    private static Bundle toBundle(IpcRequest request) {
        Bundle extras = new Bundle();
        for (Map.Entry<String, Object> entry : request.extras().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                extras.putString(key, (String) value);
            } else if (value instanceof Integer) {
                extras.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                extras.putLong(key, (Long) value);
            } else if (value instanceof Boolean) {
                extras.putBoolean(key, (Boolean) value);
            } else if (value instanceof byte[]) {
                extras.putByteArray(key, (byte[]) value);
            } else if (value instanceof IBinder) {
                extras.putBinder(key, (IBinder) value);
            } else if (value != null) {
                extras.putString(key, value.toString());
            }
        }
        return extras;
    }

    private static Map<String, Object> toMap(Bundle reply) {
        Map<String, Object> map = new LinkedHashMap<>();
        Set<String> keys = reply.keySet();
        for (String key : keys) {
            map.put(key, reply.get(key));
        }
        return map;
    }

    private static IpcException.Kind kindOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof DeadObjectException) return IpcException.Kind.DEAD_OBJECT;
            if (c instanceof SecurityException) return IpcException.Kind.REJECTED;
        }
        return IpcException.Kind.FAILURE;
    }
}
