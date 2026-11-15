package dev.nightjar.payload.hook;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Structural target matching (S03): anonymous classes found by
 * superclass/interface, methods found by name and parameter types across the class hierarchy, and
 * overloads found by name + arity + parameter type names.
 *
 * <p>Pure reflection so the JVM tests can resolve fake classes.
 *
 * <p>Thread: any (resolution happens during installation).
 */
public final class StructuralMatchers {
    /** Highest anonymous-class index tried by the anonymous matcher. */
    public static final int MAX_ANONYMOUS_INDEX = 30;

    private StructuralMatchers() {}

    /**
     * Finds {@code Outer$1…$30} whose class hierarchy contains {@code superName} (simple name or
     * fully qualified name, see {@link #typeMatches}), declares the method the hook needs, and
     * returns it, or null.
     *
     * <p>The method test matters: several anonymous classes of one outer class extend the same
     * handler base, and the first one that matches the hierarchy may not declare {@code
     * handleMessage} (HK-17).
     */
    public static Class<?> findAnonymous(ClassLoader loader, String outerName, String superName) {
        return findAnonymous(loader, outerName, superName, null, -1);
    }

    /**
     * Same, restricted to candidates that declare {@code methodName} (any signature when {@code
     * arity < 0}).
     */
    public static Class<?> findAnonymous(ClassLoader loader, String outerName, String superName,
            String methodName, int arity) {
        if (outerName == null || superName == null) return null;
        for (int i = 1; i <= MAX_ANONYMOUS_INDEX; i++) {
            Class<?> c = load(loader, outerName + "$" + i);
            if (c == null) continue;
            if (!hierarchyContains(c, superName)) continue;
            if (methodName != null && findDeclared(c, methodName, List.of(), arity) == null) continue;
            return c;
        }
        return null;
    }

    /**
     * Finds a declared constructor by parameter types and arity (HK-24). {@code arity < 0} accepts
     * the first constructor.
     */
    public static java.lang.reflect.Constructor<?> findConstructor(Class<?> owner,
            List<String> paramTypes, int arity) {
        if (owner == null) return null;
        for (java.lang.reflect.Constructor<?> c : owner.getDeclaredConstructors()) {
            if (arity >= 0 && c.getParameterCount() != arity) continue;
            if (!paramTypes.isEmpty() && !paramsMatch(c.getParameterTypes(), paramTypes)) continue;
            return c;
        }
        return null;
    }

    /** True when {@code c}, a superclass or an implemented interface matches {@code name}. */
    public static boolean hierarchyContains(Class<?> c, String name) {
        if (c == null || name == null) return false;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            if (typeMatches(k.getName(), name)) return true;
            for (Class<?> itf : k.getInterfaces()) {
                if (typeMatches(itf.getName(), name)) return true;
            }
            k = k.getSuperclass();
        }
        return false;
    }

    /**
     * Finds a method by name and parameter types, scanning the class and its superclasses
     * (the "superclass scan" of). When {@code paramTypes} is empty, any signature
     * matches; {@code arity >= 0} restricts the parameter count.
     */
    public static Method findMethod(Class<?> owner, String name, List<String> paramTypes, int arity) {
        if (owner == null || name == null) return null;
        Class<?> k = owner;
        while (k != null && k != Object.class) {
            Method m = findDeclared(k, name, paramTypes, arity);
            if (m != null) return m;
            k = k.getSuperclass();
        }
        return null;
    }

    private static Method findDeclared(Class<?> owner, String name, List<String> paramTypes, int arity) {
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (arity >= 0 && m.getParameterCount() != arity) continue;
            if (!paramTypes.isEmpty() && !paramsMatch(m.getParameterTypes(), paramTypes)) continue;
            return m;
        }
        return null;
    }

    private static boolean paramsMatch(Class<?>[] actual, List<String> declared) {
        if (actual.length != declared.size()) return false;
        for (int i = 0; i < actual.length; i++) {
            if (!typeMatches(actual[i].getName(), declared.get(i))) return false;
        }
        return true;
    }

    /**
     * Matches a declared type name against a runtime type: exact name, simple name, array notation
     * ({@code byte[]}, {@code byte[][]}: each {@code []} removes one array dimension on both sides) and
     * generic erasure ({@code ArrayList<Byte>} → {@code java.util.ArrayList}).
     */
    public static boolean typeMatches(String runtimeName, String declared) {
        if (declared == null || runtimeName == null) return false;
        String d = declared.trim();
        if (d.isEmpty() || d.equals("*")) return true;
        if (d.equals(runtimeName)) return true;

        // Generic: keep the erasure.
        int lt = d.indexOf('<');
        if (lt > 0) d = d.substring(0, lt);

        if (d.endsWith("[]")) {
            String component = d.substring(0, d.length() - 2);
            String runtimeComponent = runtimeName.startsWith("[") ? descriptor(runtimeName) : "";
            return !runtimeComponent.isEmpty() && typeMatches(runtimeComponent, component);
        }
        if (runtimeName.equals(d)) return true;
        // Simple name on either side.
        return simpleName(runtimeName).equals(simpleName(d));
    }

    /**
     * The component type of a runtime array class name, one dimension removed: {@code [[B} gives {@code [B},
     * {@code [B} gives {@code byte}, {@code [Ljava.lang.String;} gives {@code java.lang.String}.
     */
    static String descriptor(String arrayName) {
        if (arrayName.length() < 2 || arrayName.charAt(0) != '[') return "";
        if (arrayName.charAt(1) == '[') return arrayName.substring(1);
        return switch (arrayName.charAt(1)) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'L' -> arrayName.endsWith(";") ? arrayName.substring(2, arrayName.length() - 1) : "";
            default -> "";
        };
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }
}
