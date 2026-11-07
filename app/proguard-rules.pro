# Error Prone's compile-time annotation refers to a JDK compiler enum absent on Android.
-dontwarn javax.lang.model.element.Modifier

# AndroidJUnitRunner lives in a separately shrunk test APK and calls this public facade.
# Keep that binary boundary in the target APK; the rest of the release remains optimized.
-keep class androidx.tracing.Trace { *; }

# The independently shrunk instrumentation APK shares Kotlin stdlib from the target.
# Its public/protected ABI must survive even when production call sites were inlined
# (runner TestDirCalculator uses LazyKt and Lambda(int)). Names still use AGP mapping.
# This applies to the shared Kotlin library, not app/Compose/Media3 implementation code.
-keep,allowobfuscation class kotlin.** {
    public *;
    protected *;
}
# The dedicated fixture also invokes coroutine entry points absent from app call sites.
-keep,allowobfuscation class kotlinx.coroutines.BuildersKt** { public *; }
-keep,allowobfuscation class kotlinx.coroutines.DelayKt { public *; }
