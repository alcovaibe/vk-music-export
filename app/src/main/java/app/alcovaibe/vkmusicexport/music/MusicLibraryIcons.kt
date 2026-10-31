package app.alcovaibe.vkmusicexport.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal val LibraryNoteIcon=ImageVector.Builder("LibraryNote",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {moveTo(9f,4f);lineTo(20f,2f);lineTo(20f,17f);curveTo(20f,21f,13f,22f,13f,18f);curveTo(13f,15f,16f,14f,18f,15f);lineTo(18f,7f);lineTo(11f,9f);lineTo(11f,19f);curveTo(11f,23f,4f,24f,4f,20f);curveTo(4f,17f,7f,16f,9f,17f);close()}
}.build()
internal val PlaylistIcon=ImageVector.Builder("Playlists",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {moveTo(3f,5f);lineTo(14f,5f);lineTo(14f,7f);lineTo(3f,7f);close();moveTo(3f,10f);lineTo(14f,10f);lineTo(14f,12f);lineTo(3f,12f);close();moveTo(3f,15f);lineTo(10f,15f);lineTo(10f,17f);lineTo(3f,17f);close();moveTo(18f,6f);lineTo(22f,9f);lineTo(22f,11f);lineTo(20f,10f);lineTo(20f,18f);curveTo(20f,22f,13f,22f,13f,18f);curveTo(13f,15f,16f,15f,18f,16f);close()}
}.build()
internal val RecordIcon=ImageVector.Builder("Albums",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {moveTo(12f,2f);curveTo(25f,2f,25f,22f,12f,22f);curveTo(-1f,22f,-1f,2f,12f,2f);close();moveTo(12f,9f);curveTo(8f,9f,8f,15f,12f,15f);curveTo(16f,15f,16f,9f,12f,9f);close()}
}.build()
internal val SavedLibraryIcon=ImageVector.Builder("SavedMusic",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {moveTo(5f,3f);lineTo(19f,3f);lineTo(19f,22f);lineTo(12f,17f);lineTo(5f,22f);close();moveTo(7f,5f);lineTo(7f,18f);lineTo(12f,14.5f);lineTo(17f,18f);lineTo(17f,5f);close()}
}.build()
internal val MusicChevronIcon=ImageVector.Builder("OpenMusicSection",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f);lineTo(7.5f,17.5f);lineTo(13f,12f);lineTo(7.5f,6.5f);close()}
}.build()

/** Geometry from the supplied user-music.svg, centered in the navigation icon viewport. */
internal val FriendsMusicIcon=ImageVector.Builder("FriendsMusic",24.dp,24.dp,555f,555f).apply {
    addPath(addPathNodes("M 303 175.5 A 103 103 0 1 1 97 175.5 A 103 103 0 1 1 303 175.5 Z"),fill=SolidColor(Color.Black))
    addPath(addPathNodes("M 20 457.5 C 20 365.5 110 303.5 205 303.5 C 230 303.5 251 303.5 272 305.5 A 80 80 0 0 0 405 394.5 L 405 457.5 Q 405 482.5 380 482.5 L 45 482.5 Q 20 482.5 20 457.5 Z"),fill=SolidColor(Color.Black))
    addPath(addPathNodes("M 384 348.5 A 44 44 0 1 1 296 348.5 A 44 44 0 1 1 384 348.5 Z M 533 320.5 A 46 46 0 1 1 441 320.5 A 46 46 0 1 1 533 320.5 Z"),fill=SolidColor(Color.Black))
    addPath(addPathNodes("M 363 165.5 Q 363 153.5 377 150.5 L 510 124.5 Q 533 120.5 533 143.5 L 533 320.5 L 508 320.5 L 508 197.5 L 387 220.5 L 387 348.5 L 363 348.5 Z"),fill=SolidColor(Color.Black))
}.build()
