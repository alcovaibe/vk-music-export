package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

private val artistCardShape = RoundedCornerShape(22.dp)
private val releaseDate = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))

internal class ArtistTrackActions(
    private val remote: Boolean,
    private val downloadEnabled: Boolean,
    private val saved: Map<String, PublishedAudio>,
    private val savedListeningAllowed: Boolean,
    val preview: (NativeTrack) -> String?,
    private val playRemote: (ArtistTracks, Int) -> Unit,
    private val downloadRemote: (ArtistTracks, Int) -> Unit,
    private val playFile: (PublishedAudio) -> Unit,
) {
    private fun canStream(track: NativeTrack) =
        remote && track.duration > 0 && !track.restricted && track.metadataError == null

    fun canPlay(track: NativeTrack) = canStream(track) || savedListeningAllowed && track.id in saved
    fun canDownload(track: NativeTrack) = downloadEnabled && canStream(track)
    fun saved(track: NativeTrack) = saved[track.id]

    fun play(group: ArtistTracks, index: Int, track: NativeTrack) {
        if (canStream(track)) playRemote(group, index)
        else if (savedListeningAllowed) saved(track)?.let(playFile)
    }

    fun download(group: ArtistTracks, index: Int) = downloadRemote(group, index)
}

internal fun LazyListScope.artistProfileItems(
    artist: MusicArtist,
    details: MusicArtistDetails?,
    tracks: ArtistTrackActions,
    busy: Boolean,
    followed: Boolean, canFollow: Boolean, back: () -> Unit, toggle: () -> Unit,
    openQueue: (ArtistTracks) -> Unit,
    openRelease: (NativePlaylist) -> Unit,
) {
    val profile = details?.artist ?: artist
    item("artist_profile") {
        ArtistHero(profile, details, tracks, followed, canFollow, back, toggle)
    }
    if (details == null) return
    item("artist_preview") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 300.dp && LocalDensity.current.fontScale < 1.3f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (group in ArtistTracks.entries) {
                        ArtistPreviewColumn(group, details, tracks, openQueue, Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (group in ArtistTracks.entries) {
                        ArtistPreviewColumn(group, details, tracks, openQueue, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    details.latest?.let { release ->
        item("artist_latest_release") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ArtistSectionTitle("Последний релиз")
                ArtistLatestRelease(release, !busy) { openRelease(release.playlist) }
            }
        }
    }
    if (details.releases.isNotEmpty()) {
        item("artist_releases") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ArtistSectionTitle("Все релизы")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("artist_release_list")) {
                    items(details.releases, key = MusicArtistRelease::id) { release ->
                        ArtistReleaseTile(release, !busy) { openRelease(release.playlist) }
                    }
                }
            }
        }
    }
    if (details.appearances.isNotEmpty()) {
        item("artist_appearances_title") { ArtistSectionTitle("Участие в релизах") }
        items(details.appearances, key = { "artist_appearance_${it.id}" }) { release ->
            ArtistAppearanceRow(profile, release, !busy) { openRelease(release.playlist) }
        }
    }
}

@Composable
private fun ArtistHero(artist: MusicArtist, details: MusicArtistDetails?, actions: ArtistTrackActions,
    followed: Boolean, canFollow: Boolean, back: () -> Unit, toggle: () -> Unit) {
    val popularIndex = details?.popular?.indexOfFirst(actions::canPlay) ?: -1
    val group = if (popularIndex >= 0) ArtistTracks.POPULAR else ArtistTracks.ALL
    val rows = details?.tracks(group).orEmpty()
    val firstPlayable = if (popularIndex >= 0) popularIndex else rows.indexOfFirst(actions::canPlay)
    Surface(color = MaterialTheme.colorScheme.surface, shape = artistCardShape, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.35f)) {
                MusicThumbnail(url = artist.photo, artist = true, identity = artist.id,
                    modifier = Modifier.matchParentSize().testTag("artist_hero_image"), shape = RectangleShape, maxPixels = 1200)
                GlassButton(back, Modifier.align(Alignment.TopStart).padding(6.dp), true, "Назад") {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(20.dp))
                }
                val progress by animateFloatAsState(if (followed) 1f else 0f, tween(200), label = "artist subscription")
                GlassButton(toggle, Modifier.align(Alignment.TopEnd).padding(6.dp).testTag("artist_subscription")
                    .semantics { stateDescription = if (followed) "Подписан" else "Не подписан" }, canFollow,
                    if (followed) "Отписаться от исполнителя" else "Подписаться на исполнителя") {
                    Canvas(Modifier.size(22.dp)) {
                        fun point(x: Float, y: Float) = Offset(size.width * x / 24f, size.height * y / 24f)
                        val joint = point(12 - 2 * progress, 12 + 5 * progress)
                        val color = Color.White
                        val width = 2.dp.toPx()
                        drawLine(color, point(4f, 12f), joint, width, StrokeCap.Round)
                        drawLine(color, joint, point(20f, 12 - 7 * progress), width, StrokeCap.Round)
                        if (progress < 1f) drawLine(color.copy(alpha = 1 - progress),
                            point(12f, 4 + 8 * progress), point(12f, 20 - 8 * progress), width, StrokeCap.Round)
                    }
                }
                GlassButton({ rows.getOrNull(firstPlayable)?.let { actions.play(group, firstPlayable, it) } },
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).testTag("artist_play"),
                    firstPlayable >= 0, "Воспроизвести музыку исполнителя") {
                    Icon(PlayerPlayIcon, null, Modifier.size(19.dp))
                }
            }
            Text(artist.name, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GlassButton(click: () -> Unit, modifier: Modifier, enabled: Boolean, description: String,
    icon: @Composable () -> Unit) {
    IconButton(onClick = click, enabled = enabled,
        modifier = modifier.size(48.dp).semantics { contentDescription = description }) {
        Box(Modifier.size(38.dp).background(Color.Black.copy(alpha = .46f), CircleShape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .24f), Color.Transparent)), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides Color.White.copy(alpha = if (enabled) 1f else .55f)) { icon() }
        }
    }
}

@Composable
private fun ArtistPreviewColumn(
    group: ArtistTracks,
    details: MusicArtistDetails,
    actions: ArtistTrackActions,
    openQueue: (ArtistTracks) -> Unit,
    modifier: Modifier,
) {
    val tracks = details.tracks(group)
    val releases = remember(details.releases, details.appearances) {
        (details.releases + details.appearances).associateBy(MusicArtistRelease::id)
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = artistCardShape, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_open_${group.name}")
                    .clickable(enabled = !tracks.isNullOrEmpty(), role = Role.Button, onClickLabel = "Открыть очередь") { openQueue(group) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(group.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Icon(PlayerPlayIcon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (tracks.isNullOrEmpty()) {
                Text(
                    if (!details.complete) "Данные пока недоступны" else if (tracks == null) "ВК не вернул подборку" else "Нет треков",
                    modifier = Modifier.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tracks.orEmpty().take(6).forEachIndexed { index, track ->
                val release = releases[track.album?.id]
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = actions.canPlay(track), role = Role.Button) { actions.play(group, index, track) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistTrackArtwork(track, release, actions, compact = true)
                    Column(Modifier.weight(1f)) {
                        Text(track.displayTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artistDuration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index < minOf(tracks.orEmpty().size, 6) - 1) {
                    HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ArtistSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ArtistLatestRelease(release: MusicArtistRelease, enabled: Boolean, open: () -> Unit) {
    Surface(
        onClick = open, enabled = enabled, shape = artistCardShape,
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("artist_latest_open"),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicThumbnail(url = release.playlist.photo, modifier = Modifier.fillMaxWidth(.42f).aspectRatio(1f), maxPixels = 512)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(release.playlist.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                release.type?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                (release.date?.format(releaseDate) ?: release.year?.toString())?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(MusicChevronIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistReleaseTile(release: MusicArtistRelease, enabled: Boolean, open: () -> Unit) {
    Surface(
        onClick = open, enabled = enabled, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(15.dp),
        modifier = Modifier.width(88.dp).testTag("artist_album_${release.id}"),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MusicThumbnail(url = release.playlist.photo, modifier = Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(10.dp))
            Text(release.playlist.title, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.labelLarge,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(release.type.orEmpty(), Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistAppearanceRow(artist: MusicArtist, release: MusicArtistRelease, enabled: Boolean, open: () -> Unit) {
    Surface(
        onClick = open, enabled = enabled, shape = artistCardShape,
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("artist_guest_${release.id}"),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicThumbnail(url = release.playlist.photo, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(release.playlist.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                release.performer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                if (release.featuredTracks.isNotEmpty()) {
                    val titles = release.featuredTracks.joinToString(", ") { "«$it»" }
                    val label = if (release.featuredTracks.size == 1) "в треке" else "в треках"
                    Text("${artist.name} $label $titles", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(MusicChevronIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun LazyListScope.artistQueueItems(details: MusicArtistDetails, group: ArtistTracks, actions: ArtistTrackActions) {
    val tracks = details.tracks(group).orEmpty()
    val own = details.releases.associateBy(MusicArtistRelease::id)
    val other = details.appearances.associateBy(MusicArtistRelease::id)
    item("artist_queue_header") {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MusicThumbnail(url = details.artist.photo, artist = true, size = 38.dp)
                Text(details.artist.name, Modifier.padding(horizontal = 10.dp).weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artistTrackCount(tracks.size), Modifier.padding(end = 4.dp).testTag("artist_queue_count"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    itemsIndexed(tracks, key = { index, track -> "artist_queue_${index}_${track.id}" }) { index, track ->
        val appearance = other[track.album?.id]
        val release = appearance ?: own[track.album?.id]
        val subtitle = listOfNotNull(
            appearance?.performer ?: release?.type,
            track.album?.title,
        ).joinToString(" · ").ifBlank { track.artist }
        ArtistQueueRow(group, track, index, index == tracks.lastIndex, subtitle, release, actions)
    }
}

@Composable
private fun ArtistQueueRow(
    group: ArtistTracks,
    track: NativeTrack,
    index: Int,
    last: Boolean,
    subtitle: String,
    release: MusicArtistRelease?,
    actions: ArtistTrackActions,
) {
    var menu by remember { mutableStateOf(false) }
    val download = actions.canDownload(track)
    val top = if (index == 0) 22.dp else 0.dp
    val bottom = if (last) 22.dp else 0.dp
    Surface(color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().testTag("artist_queue_track_${index + 1}_${track.id}").combinedClickable(
                    enabled = actions.canPlay(track), role = Role.Button,
                    onClickLabel = "Воспроизвести ${track.displayTitle}",
                    onLongClickLabel = if (download) "Действия с треком" else null,
                    onLongClick = if (download) ({ menu = true }) else null,
                    onClick = { actions.play(group, index, track) },
                ).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((index + 1).toString(), Modifier.widthIn(min = 18.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArtistTrackArtwork(track, release, actions, compact = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(track.displayTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(track.artistDuration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Скачать трек") }, enabled = download,
                        onClick = { menu = false; actions.download(group, index) },
                    )
                }
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 94.dp, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ArtistTrackArtwork(track: NativeTrack, release: MusicArtistRelease?, actions: ArtistTrackActions, compact: Boolean) {
    val saved = actions.saved(track)
    MusicThumbnail(
        url = actions.preview(track) ?: release?.playlist?.photo,
        uri = saved?.uri,
        identity = saved?.let { it.uri + it.sha256 } ?: track.id,
        size = if (compact) 36.dp else 42.dp,
        shape = RoundedCornerShape(9.dp),
    )
}

internal fun LazyListScope.artistLoadingItems(state: MusicArtistsState, retry: () -> Unit) {
    val complete = state.album != null || state.details?.complete != false
    if (!state.busy && complete && state.error == null && state.message == null) return
    item("artist_loading") {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Загружаем музыку исполнителя…", style = MaterialTheme.typography.bodySmall)
            } else {
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(state.errorDetails ?: it.label, color = MaterialTheme.colorScheme.error) }
                if (!complete) Text("Часть данных ВК недоступна. Показано то, что удалось загрузить.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = retry) { Text("Повторить загрузку") }
            }
        }
    }
}

private val NativeTrack.artistDuration: String
    get() = "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}"

private fun artistTrackCount(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "треков"
    count % 10 == 1 -> "трек"
    count % 10 in 2..4 -> "трека"
    else -> "треков"
}
