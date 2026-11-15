package dev.nightjar.app.data.db

/**
 * Loads the SQLCipher native library before the first database open.
 *
 * `net.zetetic:sqlcipher-android` ships `libsqlcipher.so` but never loads it: the JNI methods on
 * `SQLiteConnection` only bind after `System.loadLibrary("sqlcipher")`. Without this call the first
 * `getWritableDatabase()` throws
 * `UnsatisfiedLinkError: No implementation found for ... nativeOpen` — which is what the first OnePlus
 * install showed. The library is in the APK (`lib/arm64-v8a/libsqlcipher.so`), so the missing call was
 * the whole defect.
 *
 * Loading is idempotent and cheap, so every entry point calls [ensureLoaded] instead of relying on one
 * of them having run first: the app process opens the credential-protected database, and the Direct
 * Boot provider opens the staging database in a process that may start before any Activity exists.
 *
 * A failed load is recorded in [loadError] rather than thrown here. The next database open rethrows the
 * same error with the call site in the trace, which is far more useful than a stack that ends in
 * `Application.onCreate`; [dev.nightjar.app.feature.health] reports the recorded error as a failed
 * check. JVM unit tests hit this path on every run (there is no `.so` on the host), and they are expected
 * to record an error while never opening a SQLCipher database.
 */
object SqlCipherLib {
    private const val LIBRARY = "sqlcipher"

    @Volatile private var loaded = false

    /** The load failure, if the attempt did not succeed; null once the library is loaded. */
    @Volatile var loadError: UnsatisfiedLinkError? = null
        private set

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary(LIBRARY)
            loaded = true
            loadError = null
        } catch (error: UnsatisfiedLinkError) {
            loadError = error
        }
    }
}
