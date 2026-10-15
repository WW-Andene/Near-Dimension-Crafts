# MediaPipe Tasks — keep model path strings and task classes intact
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }

# Handy — keep scanner/exporter data classes used via reflection-like JSON parsing
-keep class com.neardimension.handy.scanner.HandBiometrics { *; }
-keep class com.neardimension.handy.scanner.ModelMeta { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore preferences
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# MediaPipe 1.x — optional references the app never exercises (LiteRT-LM image
# embedder backend, graph profiler/template protos). Not on the classpath.
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$*
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$*
