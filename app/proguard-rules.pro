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

# Netty optional dependencies (not available on Android)
-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.handler.ssl.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn reactor.blockhound.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.impl.**
-dontwarn java.lang.management.**

# Keep all Netty classes used by Ktor
-keep class io.netty.** { *; }
-keep class org.slf4j.** { *; }

# Additional Netty optional deps (compression, marshalling, etc.)
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.common.**
