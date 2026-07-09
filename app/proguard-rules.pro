# MediaPipe Tasks — keep model path strings and task classes intact
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }

# Handy — keep scanner/exporter data classes used via reflection-like JSON parsing
-keep class com.arhand.scanner.HandBiometrics { *; }
-keep class com.arhand.scanner.ModelMeta { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore preferences
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
