# ONNX Runtime 需要保留 native 方法
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class * { native <methods>; }
