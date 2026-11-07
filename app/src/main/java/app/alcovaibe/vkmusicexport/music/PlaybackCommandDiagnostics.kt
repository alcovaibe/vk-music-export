package app.alcovaibe.vkmusicexport.music

/** Opt-in instrumentation observer. No logging or command filtering in normal playback. */
internal object PlaybackCommandDiagnostics {
    var observer: ((String) -> Unit)? = null

    inline fun record(event: () -> String) {
        observer?.invoke(event())
    }
}
