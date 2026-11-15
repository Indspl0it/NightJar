package dev.nightjar.app.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import dev.nightjar.payload.hook.HookBackend
import dev.nightjar.payload.hook.HookKind
import dev.nightjar.payload.hook.Hooker
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [HookBackend] over the Xposed API that Vector and LSPosed provide. Nightjar carries no hook engine of its own: a
 * second LSPlant inside `com.android.phone` next to the framework's is exactly what must not happen.
 *
 * The hooked method is replaced by [Hooker.call], which runs the before/after phases and reaches the original through
 * [XposedBridge.invokeOriginalMethod]. That call runs the original method directly: a callback that another module put
 * on the same method with a lower priority does not run for this call. Nightjar accepts this because the skip path and
 * the HK-14 shield must own the whole call.
 */
class XposedBackend : HookBackend {
    private val handles = AtomicLong()
    private val unhooks = ConcurrentHashMap<Long, XC_MethodHook.Unhook>()

    override fun available(): Boolean =
        try {
            XposedBridge.getXposedVersion() > 0
        } catch (_: Throwable) {
            false
        }

    override fun install(
        target: Member?,
        kind: HookKind?,
        hooker: Hooker?,
    ): Long {
        if (target == null || hooker == null) return 0L
        return try {
            hooker.setOriginal { receiver, params -> XposedBridge.invokeOriginalMethod(target, receiver, params) }
            val unhook =
                XposedBridge.hookMethod(
                    target,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            val params = param.args ?: emptyArray()
                            val args =
                                if (hooker.staticTarget()) {
                                    params
                                } else {
                                    arrayOfNulls<Any?>(params.size + 1).also {
                                        it[0] = param.thisObject
                                        System.arraycopy(params, 0, it, 1, params.size)
                                    }
                                }
                            return hooker.call(args)
                        }
                    },
                ) ?: return 0L
            val handle = handles.incrementAndGet()
            unhooks[handle] = unhook
            handle
        } catch (_: Throwable) {
            0L
        }
    }

    override fun uninstall(handle: Long): Boolean {
        val unhook = unhooks.remove(handle) ?: return false
        return try {
            unhook.unhook()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Asks LSPosed to deoptimize [target], so a caller that ART compiled with the small method inlined calls the
     * hooked method again. `XposedBridge.deoptimizeMethod` is an LSPosed extension of the Xposed API, so it is called
     * reflectively. False when the framework does not have it.
     */
    override fun forceDeoptimize(target: Member?): Boolean {
        if (target == null) return false
        return try {
            val deoptimize = XposedBridge::class.java.getMethod("deoptimizeMethod", Member::class.java)
            deoptimize.invoke(null, target) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
