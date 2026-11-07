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

# External R8 playback instrumentation is compiled independently from the target.
# Preserve only the concrete boundary it invokes; class names remain mapped/obfuscated.
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.PlaybackRuntime {
    public static ** INSTANCE;
    public *** getState(...);
    public *** stop(...);
    public *** play*(...);
    public *** repeat(...);
    public *** toggle(...);
    public *** seek(...);
    public *** flushHistory*(...);
}
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.ExportLayout {
    public static ** INSTANCE;
    public *** task*(...);
}
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.SharedAudioStore {
    public <init>(...);
    public *** publish*(...);
}
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.NativeTrack { public <init>(...); }
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.TransferResult { public <init>(...); }
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.ProcessedAudio { public <init>(...); }
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.PlaybackEntry { public <init>(...); }
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.PublishedAudio {
    public *** getTrack(...);
    public *** getUri(...);
}
-keep,allowobfuscation class app.alcovaibe.vkmusicexport.music.PlaybackState {
    public *** getPlaying(...);
    public *** getSeekable(...);
    public *** getRepeat(...);
    public *** getPlayWhenReady(...);
    public *** getError(...);
    public *** getEntries(...);
}
-keep,allowobfuscation class kotlinx.coroutines.Dispatchers { public *; }
-keep,allowobfuscation class kotlinx.coroutines.TimeoutKt { public *; }
