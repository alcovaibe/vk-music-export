package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.MusicTheme

/**
 * IDE-only sample screens. No credentials, network connection or ViewModel is needed.
 * The same shared scaffold and rows used by the production screens are rendered here.
 */
private val previewTracks=listOf(
    NativeTrack("preview_library_1","Музыка нас связала","Мираж",263),
    NativeTrack("preview_library_2","Новый герой","Мираж",250),
    NativeTrack("preview_library_3","Я больше не прошу","Мираж",268)
)

@Preview(name="Библиотека — главный экран",showBackground=true,widthDp=393,heightDp=800)
@Composable
private fun LibraryScreenPreview() {
    MusicTheme {
        MusicLibraryScaffold("Библиотека",MusicDestination.LIBRARY,{},ExportSnapshot(),{},{},{}) {
            item("categories") {
                MusicCategoryGrid(LibraryCategory.TRACKS,
                    mapOf(LibraryCategory.TRACKS to 124,LibraryCategory.PLAYLISTS to 8,
                        LibraryCategory.ALBUMS to 5,LibraryCategory.ARTISTS to 26),true,{})
            }
            item("title") { Text("Мои треки",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
            itemsIndexed(previewTracks) { index,track->
                MusicTrackRow(track,index+1,onPlay={},onDownload={})
            }
        }
    }
}

@Preview(name="Сохранённое — экран",showBackground=true,widthDp=393,heightDp=800)
@Composable
private fun SavedScreenPreview() {
    MusicTheme {
        MusicLibraryScaffold("Сохранённое",MusicDestination.SAVED,{},ExportSnapshot(),{},{},{}) {
            item("tools") {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Сохранённая музыка",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("Треки, доступные для прослушивания без подключения",
                        style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(previewTracks) { index,track->
                MusicTrackRow(track,index+1,onPlay={})
            }
        }
    }
}

@Preview(name="Друзья — экран",showBackground=true,widthDp=393,heightDp=800)
@Composable
private fun FriendsScreenPreview() {
    MusicTheme {
        MusicLibraryScaffold("Друзья",MusicDestination.FRIENDS,{},ExportSnapshot(),{},{},{},
            prominentTitle=true) {
            item("title") {
                Text("Музыка друзей",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            }
            item("friend1") {
                MusicCollectionRow("Александра","Список музыки",tag="preview_friend_1",onClick={})
            }
            item("friend2") {
                MusicCollectionRow("Алексей","Список музыки",tag="preview_friend_2",onClick={})
            }
        }
    }
}

@Preview(name="Загрузки — очередь",showBackground=true,widthDp=393,heightDp=800)
@Composable
private fun DownloadsScreenPreview() {
    val queue=ExportSnapshot(status="READY",total=3,done=1,pending=2,queueId="preview")
    MusicTheme {
        MusicLibraryScaffold("Загрузки",MusicDestination.DOWNLOADS,{},queue,{},{},{},showBack=true) {
            downloadsItems(queue,
                DownloadsPageState(page=ExportQueuePage(queueId="preview",tab=DownloadsTab.QUEUE)),
                DownloadsTab.QUEUE,false,null,
                onTab={},onPage={},onReload={},onPause={},onResume={},onRetry={},onClear={},
                onReport={},onSourceRetry={_,_->},onOpenSaved={},canOpenSaved=false)
        }
    }
}

@Preview(name="Мини-плеер",showBackground=true,widthDp=393,heightDp=150)
@Composable
private fun MiniPlayerPreview() {
    MusicTheme {
        PlaybackMiniPlayer(
            PlaybackState(entries=listOf(
                PlaybackEntry(previewTracks[0],"Моя музыка")
            ),playWhenReady=true),
            onOpen={}
        )
    }
}
