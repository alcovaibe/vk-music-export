# Error Prone's compile-time annotation refers to a JDK compiler enum absent on Android.
-dontwarn javax.lang.model.element.Modifier

# AndroidJUnitRunner lives in a separately shrunk test APK and calls this public facade.
# Keep that binary boundary in the target APK; the rest of the release remains optimized.
-keep class androidx.tracing.Trace { *; }
