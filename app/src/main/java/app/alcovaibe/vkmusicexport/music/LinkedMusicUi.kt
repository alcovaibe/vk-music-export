package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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

internal fun openLinkInVk(context: Context, url: String): Boolean =
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.vkontakte.android"))
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть ссылку в приложении ВК.", Toast.LENGTH_SHORT).show()
    }.isSuccess

internal fun openMusicLinkSettings(context: Context) {
    val app = Uri.fromParts("package", context.packageName, null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app)) }.isSuccess) return
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
}

@Composable
internal fun MusicLinkInput(onOpen: (String) -> Unit, onLinkSettings: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть ссылку ВК") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(4096) }, label = { Text("Ссылка на трек, плейлист или альбом") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("vk_link_input"))
                TextButton(onClick = onLinkSettings, modifier = Modifier.testTag("vk_link_settings")) {
                    Text("Настроить открытие ссылок ВК")
                }
            }
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
    onOpenHere: () -> Unit,
    onOpenVk: (String) -> Unit,
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
                state.awaitingChoice -> {
                    Text("Где открыть ссылку ВК?")
                    Button(onClick = onOpenHere, modifier = Modifier.fillMaxWidth().testTag("vk_link_open_here")) {
                        Text("В VK Music Export")
                    }
                    OutlinedButton(onClick = { onOpenVk(state.link.url) },
                        modifier = Modifier.fillMaxWidth().testTag("vk_link_open_official")) {
                        Text("В приложении ВК")
                    }
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
        val shareUrl = VkMusicLinks.collectionUrl(collection)
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
            if (shareUrl != null) TextButton(onClick = { onShare(shareUrl) }) { Text("Поделиться ссылкой") }
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
