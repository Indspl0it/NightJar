package dev.nightjar.app.core.ipc

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The app's signing certificate as `settings.json` carries it.
 *
 * The payload compares this value against the caller's certificate and **withholds message content** when it does not
 * match. An empty string is a mismatch, so it must never be sent: a missing certificate is a hard error for the app
 * side rather than a silent "no events".
 */
object CertFingerprint {
    /** SHA-256 of [der], upper-case hex separated by `:` (example format). */
    fun ofCertificate(der: ByteArray): String = ofDigest(MessageDigest.getInstance("SHA-256").digest(der))

    /** Upper-case, colon-separated form of an already computed SHA-256 digest. */
    fun ofDigest(digest: ByteArray): String = digest.joinToString(":") { "%02X".format(it) }

    /** The fingerprint the running package is signed with, or null when the platform cannot tell. */
    fun ofPackage(
        context: Context,
        packageName: String = context.packageName,
    ): String? {
        val info =
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return ofCertificate(signer.toByteArray())
    }
}
