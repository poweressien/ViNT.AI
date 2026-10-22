-keep class ng.pidgingpt.inference.LlamaEngine { native <methods>; }
# sherpa-onnx: JNI looks up these classes, fields and methods by name (including the typed Function1.invoke used for TTS callbacks).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ng.pidgingpt.voice.** { *; }
-keepclassmembers class * implements kotlin.jvm.functions.Function1 { public java.lang.Object invoke(java.lang.Object); public java.lang.Integer invoke(float[]); }
