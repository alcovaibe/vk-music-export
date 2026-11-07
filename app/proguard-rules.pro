# Error Prone's compile-time annotation refers to a JDK compiler enum absent on Android.
-dontwarn javax.lang.model.element.Modifier

# AndroidJUnitRunner lives in a separately shrunk test APK and calls this public facade.
# Keep that binary boundary in the target APK; the rest of the release remains optimized.
-keep class androidx.tracing.Trace { *; }

# The independently shrunk instrumentation APK shares Kotlin's runtime from the target.
# Keep its JVM-internal ABI (including Lambda(int)); names may still be obfuscated
# through AGP's shared mapping, while app/Compose/Media3 code remains optimized.
-keep,allowobfuscation class kotlin.jvm.internal.** { *; }
