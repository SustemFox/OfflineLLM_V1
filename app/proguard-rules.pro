# Keep llama.cpp native methods
-keep class com.example.offlinellm.llama.LlamaBridge { *; }
-keepclassmembers class com.example.offlinellm.llama.LlamaBridge { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Ktor classes
-keep class io.ktor.** { *; }
-keep class org.slf4j.** { *; }

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
