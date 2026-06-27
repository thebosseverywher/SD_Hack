# Keep kotlinx.serialization generated serializers for the wire-protocol data classes.
# Field names MUST stay intact to remain wire-compatible with the desktop engine.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class com.flow.app.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class com.flow.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ONNX Runtime (when enabled).
-keep class ai.onnxruntime.** { *; }
# Tink (AEAD).
-keep class com.google.crypto.tink.** { *; }
