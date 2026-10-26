package app.alcovaibe.vkmusicexport.updates

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Transparent, theme-tinted two-arrow symbol based on the supplied reference. */
internal val UpdateIcon = ImageVector.Builder("CheckUpdates", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        pathData = addPathNodes("M3.5,10 A8.7,8.7 0,0 1,19 6.5 M19,2.8 V7.4 H14.4 M20.5,14 A8.7,8.7 0,0 1,5 17.5 M5,21.2 V16.6 H9.6"),
        stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
}.build()

