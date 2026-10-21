package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CollectionMergeTest {
    private val track=Track("12_34","Песня 🎵","Артист")
    @Test fun nativePageReplacesOnlyItsOwnSourceAndPreservesDuplicatePositionsAndPartialStatus() {
        val old=MusicSource("my-music","Моя музыка","https://vk.ru/audio",listOf(Entry("pos:0",track,1)))
        val other=old.copy(id="playlist:12:3",title="Плейлист")
        val page=old.copy(entries=listOf(Entry("pos:100",track,101),Entry("pos:101",track,102)),expectedCount=4449,status=ScanStatus.PARTIAL,reason="NATIVE_ACCESS_PARTIAL")
        val before=CollectionSnapshot(sources=listOf(old,other))
        val after=CollectionMerge.replaceSource(before,page)
        assertEquals(listOf(page,other),after.sources);assertEquals(listOf(101,102),after.sources.first().entries.map{it.position})
        assertEquals(3,after.entriesCount);assertEquals(1,after.uniqueTracks().size)
        assertEquals(before.sources,listOf(old,other));assertEquals(after.sources,CollectionMerge.replaceSource(after,page).sources)
        assertFalse(after.playlistDiscoveryConfirmed)
    }
    @Test fun previousCollectionSchemaStillReadsWithUnknownFieldsAndWithoutBrowserImportCode() {
        val raw="""{"schemaVersion":1,"updatedAt":"old","sources":[{"id":"my-music","title":"Старое","url":"https://vk.ru/audio","entries":[{"key":"0","position":1,"track":{"vkId":"12_34","title":"Песня","artist":"Артист"}}],"status":"PARTIAL","reason":"USER_STOPPED","unused":1}],"oldExtra":true}"""
        val saved=Json{ignoreUnknownKeys=true}.decodeFromString<CollectionSnapshot>(raw)
        assertEquals(1,saved.entriesCount);assertEquals(ScanStatus.PARTIAL,saved.sources.single().status)
        val unknown=Track(title="Песня",artist="Артист")
        val a=saved.sources.single().copy(entries=listOf(Entry("0",unknown,1),Entry("1",unknown,2)))
        assertEquals(4,CollectionSnapshot(sources=listOf(a,a.copy(id="second"))).uniqueTracks().size)
    }
}
