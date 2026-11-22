package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal sealed interface CollectionChange {
    data class Add(val tracks: List<NativeTrack>) : CollectionChange
    data class Remove(val tracks: List<NativeTrack>) : CollectionChange
    data class Move(val track: NativeTrack, val position: Int) : CollectionChange
    data object Delete : CollectionChange
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionEditor(list: NativePlaylist, page: NativePage, library: List<NativeTrack>,
    busy: Boolean, error: String?, onChange: (CollectionChange) -> Unit, onDismiss: () -> Unit) {
    var adding by remember(list.key) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember(page, adding) { mutableStateOf(emptySet<String>()) }
    val ids = remember(page) { page.tracks.groupingBy { it.id }.eachCount() }
    val rows = remember(page, library, adding, query) {
        if (!adding) page.tracks else library.distinctBy { it.id }.filter {
            it.id !in ids && (it.artist.contains(query,true) || it.displayTitle.contains(query,true))
        }
    }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).navigationBarsPadding()) {
            Text(list.title, style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={adding=!adding;query=""},enabled=!busy) {
                Text(if (adding) "К трекам подборки" else "Добавить из «Моей музыки»")
            }
            if (adding) OutlinedTextField(query,{query=it},placeholder={Text("Исполнитель или трек")},singleLine=true)
            if (error != null) Text(error,color=MaterialTheme.colorScheme.error)
            if (rows.isEmpty()) Text(if (adding) "Нет подходящих треков" else "Подборка пуста")
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false)) {
                itemsIndexed(rows) { index, track ->
                    val unique = adding || ids[track.id] == 1
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(track.id in selected, {
                            selected = if (track.id in selected) selected-track.id else selected+track.id
                        }, enabled=!busy && unique)
                        Column(Modifier.weight(1f)) {
                            Text(track.displayTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(track.artist,style=MaterialTheme.typography.bodySmall,maxLines=1)
                        }
                        if (!adding) {
                            IconButton(onClick={onChange(CollectionChange.Move(track,index-1))},enabled=!busy && unique && index>0) {
                                Icon(Icons.Default.KeyboardArrowUp,"Переместить выше")
                            }
                            IconButton(onClick={onChange(CollectionChange.Move(track,index+1))},enabled=!busy && unique && index<rows.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown,"Переместить ниже")
                            }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick={
                val tracks=(if (adding) library else page.tracks).filter { it.id in selected }.distinctBy { it.id }
                onChange(if (adding) CollectionChange.Add(tracks) else CollectionChange.Remove(tracks))
            },enabled=!busy && selected.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {
                Text(if (adding) "Добавить выбранные (${selected.size})" else "Убрать из подборки (${selected.size})")
            }
        }
    }
}
