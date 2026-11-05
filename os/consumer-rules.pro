# AeonRuntime OS Module — ProGuard/R8 consumer rules
# Applied automatically to any app that depends on :os

-keep class com.aeoncorex.aeonruntime.os.** { public *; }
-keep class com.aeoncorex.aeonruntime.os.distro.** { *; }
-keep class com.aeoncorex.aeonruntime.os.registry.** { *; }
-keep class com.aeoncorex.aeonruntime.os.settings.** { *; }
-keep class com.aeoncorex.aeonruntime.os.gui.** { *; }
-keep class com.aeoncorex.aeonruntime.os.gui.vnc.** { *; }
-keep class com.aeoncorex.aeonruntime.os.gui.compose.** { *; }

# Sealed class subclasses — required for when() exhaustive checks
-keep class com.aeoncorex.aeonruntime.os.distro.DistroSetupState$* { *; }
-keep class com.aeoncorex.aeonruntime.os.gui.vnc.VncState$* { *; }
-keep class com.aeoncorex.aeonruntime.os.gui.DesktopSessionState$* { *; }
-keep class com.aeoncorex.aeonruntime.os.SuResult$* { *; }
-keep class com.aeoncorex.aeonruntime.os.distro.Distro$* { *; }

-keepclassmembers class com.aeoncorex.aeonruntime.os.distro.Distro {
    public static ** Companion;
    public static ** all;
    public static ** fromId(...);
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.flow.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }

# DES cipher for VNC authentication
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.SecretKeySpec { *; }
-keep class javax.crypto.Cipher { *; }
