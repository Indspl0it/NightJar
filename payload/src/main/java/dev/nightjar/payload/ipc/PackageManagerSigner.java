package dev.nightjar.payload.ipc;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@link AppSigner} over {@link PackageManager}. Device only: the payload runs in {@code com.android.phone},
 * which may query any package.
 *
 * <p>Thread: IPC worker.
 */
public final class PackageManagerSigner implements AppSigner {

    private final PackageManager packageManager;

    public PackageManagerSigner(PackageManager packageManager) {
        this.packageManager = packageManager;
    }

    @Override
    public String signatureSha256(String packageName) {
        if (packageManager == null || packageName == null) return null;
        try {
            PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info == null ? null : info.signingInfo;
            if (signing == null) return null;
            // The app pins the current signers, so the payload must read the same list
            // (the history starts with the oldest certificate after a key rotation).
            Signature[] signatures = signing.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) return null;
            return sha256(signatures[0].toByteArray());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public int uidOf(String packageName) {
        if (packageManager == null || packageName == null) return -1;
        try {
            return packageManager.getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return -1;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder(digest.length * 3);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) b.append(':');
                int v = digest[i] & 0xFF;
                b.append(Character.toUpperCase(Character.forDigit(v >> 4, 16)));
                b.append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
