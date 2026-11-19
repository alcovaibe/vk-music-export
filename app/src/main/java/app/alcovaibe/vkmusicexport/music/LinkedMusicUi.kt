package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal fun shareVkMusicLink(context: Context, url: String) {
    val outbound = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(outbound, null))
}

@Composable
internal fun MusicLinkInput(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть ссылку ВК") },
        text = {
            OutlinedTextField(text, { text = it.take(4096) }, label = { Text("Ссылка на трек, плейлист или альбом") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("vk_link_input"))
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOpen(text) }) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

internal fun LazyListScope.linkedMusicItems(
    state: MusicLinkState,
    initialized: Boolean,
    configured: Boolean,
    canDownload: Boolean,
    notice: String?,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (LinkedMusic, Int) -> Unit,
    onDownload: (LinkedMusic, Int) -> Unit,
    onDownloadCollection: (LinkedMusic) -> Unit,
    onShare: (String) -> Unit,
    onPaste: () -> Unit,
    preview: (NativeTrack) -> String?
) {
    item("vk_link_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.link == null -> {
                    Text("Ожидается ссылка ВК на трек, плейлист или альбом.")
                    TextButton(onClick = onPaste) { Text("Вставить другую ссылку") }
                }
                !initialized || state.loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Открываем музыку ВК…")
                }
                !configured -> {
                    Text("Подключи аккаунт ВК, чтобы открыть музыку по ссылке.")
                    Button(onClick = onConnect) { Text("Подключить ВК") }
                }
                state.error != null -> {
                    Text(
                        if (state.error == MusicError.UNAVAILABLE) "ВК не вернул запись или подборку по этой ссылке."
                        else state.error.label,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("Повторить") }
                        if (state.error in setOf(MusicError.ACCESS, MusicError.INVALID_ACCESS, MusicError.VALIDATION))
                            TextButton(onClick = onConnect) { Text("Музыкальный доступ") }
                    }
                }
            }
            notice?.let { Text(it, modifier = Modifier.testTag("vk_link_action_status")) }
        }
    }
    val music = state.music ?: return
    val collection = music.list
    if (collection != null) item("vk_link_collection_actions") {
        val available = music.page.tracks.count { it.duration > 0 && !it.restricted && it.metadataError == null }
        val link = state.link
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (music.page.count == 0) "Подборка пуста." else "Доступно для воспроизведения $available из ${music.page.count}")
            Button(enabled = available > 0, onClick = { onPlay(music, -1) }, modifier = Modifier.fillMaxWidth()) { Text("Слушать") }
            OutlinedButton(enabled = available > 0 && canDownload, onClick = { onDownloadCollection(music) },
                modifier = Modifier.fillMaxWidth().testTag("vk_link_download_collection")) {
                Text(when (collection.kind) {
                    PlaylistKind.ALBUM -> "Скачать альбом"
                    PlaylistKind.PLAYLIST -> "Скачать плейлист"
                    PlaylistKind.UNKNOWN -> "Скачать подборку"
                })
            }
            if (link != null) TextButton(onClick = { onShare(link.url) }) { Text("Поделиться ссылкой") }
        }
    }
    itemsIndexed(music.page.tracks, key = { index, track -> "vk_link_${index}_${track.id}" }) { index, track ->
        val playable = track.duration > 0 && !track.restricted && track.metadataError == null
        val trackUrl = VkMusicLinks.track(track.id, track.accessKey)?.url
        MusicTrackRow(track, index + 1, preview = preview(track), enabled = canDownload,
            onPlay = if (playable) ({ onPlay(music, index) }) else null,
            onDownload = if (playable) ({ onDownload(music, index) }) else null,
            onShare = trackUrl?.let { url -> { onShare(url) } },
            tag = "vk_link_track_$index")
    }
}
