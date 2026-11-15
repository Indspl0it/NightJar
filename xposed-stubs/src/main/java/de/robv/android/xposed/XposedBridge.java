package de.robv.android.xposed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;

public final class XposedBridge {
    private XposedBridge() {}

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        throw new UnsupportedOperationException("stub");
    }

    public static int getXposedVersion() {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }
}
