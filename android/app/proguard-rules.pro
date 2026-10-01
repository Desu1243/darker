# ML Kit discovers its registrars through Android manifest metadata and
# Firebase's component runtime. R8 full-mode optimization can otherwise remove
# or rewrite part of that graph, causing TextRecognition.getClient() to return
# a component with missing dependencies in release builds.
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep interface com.google.firebase.components.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
