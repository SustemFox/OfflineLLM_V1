# Keep llama.cpp JNI
-keep class com.example.offlinellm.llama.LlamaBridge { *; }
-keepclassmembers class com.example.offlinellm.llama.LlamaBridge { *; }
-keep class com.example.offlinellm.llama.LlamaBridge$TokenCallback { *; }
-keepclassmembers class com.example.offlinellm.llama.LlamaBridge$TokenCallback { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Ktor / Netty
-keep class io.ktor.** { *; }
-keep class org.slf4j.** { *; }
-keep class io.netty.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.handler.ssl.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn reactor.blockhound.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.impl.**
-dontwarn java.lang.management.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.common.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.google.errorprone.**
-dontwarn org.bouncycastle.**
-dontwarn com.aayushatharva.**
-dontwarn io.netty.internal.**
-ignorewarnings
