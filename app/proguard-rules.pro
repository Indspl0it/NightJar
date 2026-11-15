# Rules are added per feature.

# SQLCipher binds its JNI methods by name in `libsqlcipher.so`. R8 cannot follow a symbol into native
# code, so the class and the native declarations have to survive obfuscation or the first database open
# fails with `UnsatisfiedLinkError: No implementation found for ... nativeOpen`.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# The payload and the Xposed entry are reached by Vector/LSPosed and by hook-spec reflection, never by static references.
# HOOKS-X3: the whole payload is kept on purpose. R8 cannot see the reflective lookups, so a narrower keep needs a device
# run of every hook first. The cost is the dead and test-only payload classes in the APK.
-keep class dev.nightjar.payload.** { *; }
-keep class dev.nightjar.app.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Telephony classes the payload touches live in the phone process (com.android.phone), not in this APK.
-dontwarn com.android.internal.telephony.**
