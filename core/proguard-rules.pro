# AeonRuntime proguard rules
-keep class com.aeoncorex.aeonruntime.** { *; }
-keepclassmembers class com.aeoncorex.aeonruntime.** { *; }
-keep interface com.aeoncorex.aeonruntime.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
