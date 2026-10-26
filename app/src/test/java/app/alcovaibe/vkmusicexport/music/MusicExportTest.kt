package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MusicExportTest {
    private fun track(i: Int)=NativeTrack("12_${i+1}","Название $i","Исполнитель",2)
    @Test fun all4449PositionsAreFetchedBeyondTenWithShortPagesAndDuplicatesPreserved()=runBlocking {
        val input=(0 until 4449).map(::track).toMutableList().apply{this[7]=this[6]}
        val offsets=mutableListOf<Int>()
        val page=MusicPagination.tracks({offset->offsets.add(offset);NativePage(input.drop(offset).take(73),input.size)})
        assertEquals(input,page.tracks);assertEquals(4449,page.count)
        assertEquals(listOf(0,73,146),offsets.take(3));assertTrue(offsets.size>10)
    }
    @Test fun changingCountPrematureEndRepeatedPageAndCancellationCannotPretendSuccess()=runBlocking {
        suspend fun fails(expected: MusicError,fetch:suspend(Int)->NativePage) {
            try{MusicPagination.tracks(fetch);fail("must reject incomplete scan")}
            catch(e: MusicFailure){assertEquals(expected,e.code)}
        }
        fails(MusicError.LIBRARY_CHANGED){NativePage(listOf(track(it)),if(it==0)2 else 3)}
        fails(MusicError.INCOMPLETE){NativePage(if(it==0)listOf(track(0))else emptyList(),2)}
        fails(MusicError.INCOMPLETE){NativePage(listOf(track(0)),2)}
        var calls=0
        val job=launch(start=CoroutineStart.UNDISPATCHED){MusicPagination.tracks({calls++;awaitCancellation()})}
        job.cancelAndJoin();assertEquals(1,calls)
    }
    @Test fun sparseCatalogReportsOriginalTotalAndTerminatesWithoutInventingMissingLists()=runBlocking {
        val list=NativePlaylist(12,34,"Плейлист",1,"SECRET",null,null,1)
        val offsets=mutableListOf<Int>()
        val result=MusicPagination.catalog({offset->offsets.add(offset);NativeCatalog(if(offset==0)listOf(list)else emptyList(),395)})
        assertEquals(listOf(0,1),offsets);assertEquals(395,result.count);assertEquals(1,result.lists.size)
        val repeated=MusicPagination.catalog({NativeCatalog(listOf(list),395)})
        assertEquals(1,repeated.lists.size)
    }
    @Test fun folderNamesAreBoundedUnicodeSafeAndDuplicatePositionsHaveDistinctIdentity() {
        val t=track(0).copy(title="../ Очень / длинное: название? "+"😀".repeat(200),artist="CON / ..")
        assertEquals("Без названия",ExportLayout.component("...///"))
        val component=ExportLayout.component("😀".repeat(100))
        assertTrue(component.toByteArray().size<=110);assertFalse(component.endsWith("\uD83D"))
        val page=NativePage(listOf(t,t),2)
        val list=NativePlaylist(-12,34,"../ Сборник / ?",2,"PRIVATE_ACCESS_KEY",null,null,null,PlaylistKind.PLAYLIST)
        val tasks=ExportLayout.playlist(page,list)
        assertNotEquals(tasks[0].key,tasks[1].key);assertEquals(ExportLayout.name(tasks[0],"mp3"),ExportLayout.name(tasks[1],"mp3"))
        for(task in tasks) {
            assertTrue(ExportLayout.relative(task).startsWith("Music/VK Music Export/Плейлисты/"))
            assertFalse(ExportLayout.relative(task).contains("../"));assertTrue(ExportLayout.name(task,"mp3").toByteArray().size<255)
            assertFalse(Json.encodeToString(task).contains("PRIVATE_ACCESS_KEY"))
        }
        val albums=ExportLayout.library(NativePage(listOf(track(0).copy(album=MusicAlbum("album:42","Альбом"))),1))
        assertEquals(2,albums.size);assertTrue(ExportLayout.relative(albums[1]).contains("/Альбомы/"))
    }
    @Test fun visibleNamesOmitOnlyGeneratedNumbersWhileOldQueueIdentityAndRealTitleNumbersRemain() {
        val t=track(0).copy(title="1984 [2026]",artist="Artist 123")
        val list=NativePlaylist(607277757,535,"Солнечный день [2020]",1,null,null,null,null,PlaylistKind.PLAYLIST)
        val task=ExportLayout.playlist(NativePage(listOf(t),1),list).single()
        assertEquals("Artist 123 — 1984 [2026].mp3",ExportLayout.name(task,"mp3"))
        assertEquals("Artist 123 — 1984 [2026] (2).m4a",ExportLayout.name(task,"m4a",2))
        assertEquals("Music/VK Music Export/Плейлисты/Солнечный день [2020]/",ExportLayout.relative(task))
        assertEquals("Music/VK Music Export/Плейлисты/Солнечный день [2020] (2)/",ExportLayout.relative(task,2))
        val legacy="""{"key":"${task.key}","track":${Json.encodeToString(t)},"folders":["Плейлисты","Солнечный день [2020] [607277757_535]"],"position":1}"""
        assertEquals(task,Json.decodeFromString<AudioExportTask>(legacy))
        assertEquals(task.key,ExportLayout.task(t,listOf("Плейлисты","Солнечный день [2020] [607277757_535]"),1).key)
        for(id in listOf("album:42","-12_42")) {
            val album=ExportLayout.library(NativePage(listOf(t.copy(album=MusicAlbum(id,"Альбом [123]"))),1)).last()
            assertEquals("Music/VK Music Export/Альбомы/Альбом [123]/",ExportLayout.relative(album))
        }
    }
    @Test fun actualClientRequestsHundredPositionsAndExplicitOffsetWithoutLoggingAccessOrMetadata()=runBlocking {
        val requests=mutableListOf<Request>();val events=mutableListOf<String>();val token="vk1.a."+"SECRET".repeat(12)
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val request=chain.request();requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"response":{"count":4449,"items":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api=VkAudioClient(client){t,f->events.add(t+f.toString())};val access=MusicAccess(token,"Client/1")
        api.libraryPage(access,12,200);api.playlistPage(access,NativePlaylist(12,34,"PRIVATE_TITLE",2,"PRIVATE_KEY",null,null,null),300)
        requests.forEachIndexed{i,r->val form=r.body as FormBody;val fields=(0 until form.size).associate{form.name(it) to form.value(it)}
            assertEquals("100",fields["count"]);assertEquals(if(i==0)"200"else"300",fields["offset"])
            assertNull(r.url.query);assertNull(r.header("Cookie"));assertNull(r.header("Authorization"))
        }
        assertFalse(events.toString().contains("SECRET"));assertFalse(events.toString().contains("PRIVATE"))
    }
}
