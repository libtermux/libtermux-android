# AeonRuntime — shizuku module ProGuard rules

# Keep all Shizuku runtime classes
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }

# Keep all ShizukuRuntimeBridge public API
-keep class com.aeoncorex.aeonruntime.shizuku.** { *; }
-keepclassmembers class com.aeoncorex.aeonruntime.shizuku.** { *; }

# Shizuku permission callback
-keepclassmembers class * implements rikka.shizuku.Shizuku$OnRequestPermissionResultListener {
    public <methods>;
}
