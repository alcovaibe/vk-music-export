package app.alcovaibe.vkmusicexport.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.webkit.WebView

/** Counts draw callbacks only. No pixels, page text, HTML or form values are captured. */
class DiagnosticWebView(context: Context) : WebView(context) {
    var diagnosticDrawCount = 0L
        private set
    var diagnosticLastDrawMs = 0L
        private set
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        diagnosticDrawCount++
        diagnosticLastDrawMs=SystemClock.elapsedRealtime()
    }
}
