package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FriendMusicTest {
    @Test fun handlesComeFromVkAndNeverFromNameOrAnUntrustedUrl() {
        val user=FriendMusicProtocol.user(Json.parseToJsonElement("""{"id":34,"first_name":"Алина","last_name":"Смирнова","domain":"ivl609","photo_200":"https://evil.test/private"}"""))
        assertEquals("@ivl609",user.handle);assertEquals("Алина Смирнова",user.name);assertNull(user.photo)
        val missing=FriendMusicProtocol.user(Json.parseToJsonElement("""{"id":35,"first_name":"Алина","domain":"https://vk.com/private"}"""))
        assertNull(missing.handle);assertEquals("https://vk.com/id35",missing.vkUrl)
        try{FriendMusicProtocol.profile(Json.parseToJsonElement("""[{"id":35,"first_name":"Друг"}]"""),34);fail("Different profile must be rejected")}
        catch(e: MusicFailure){assertEquals(MusicError.RESPONSE,e.code)}
    }
    @Test fun queueAccountIsTheViewerAndFriendAndOriginalTrackOwnersArePreserved() {
        val user=MusicFriend(34,"Имя","Фамилия","@friend")
        val song=NativeTrack("90_7","Трек","Исполнитель",90)
        val page=NativePage(listOf(song,song),2)
        val library=FriendMusicExport.tasks(page,user,12)
        assertEquals(listOf(1,2),library.map{it.position});assertEquals(listOf(1,2),library.map{it.occurrence})
        assertTrue(library.all{it.source?.account==12L&&it.source.id=="34"&&it.track.id=="90_7"})
        assertNotEquals(library[0].key,library[1].key)
        val playlist=NativePlaylist(-99,8,"Подборка",2,null,null,null,null,PlaylistKind.PLAYLIST)
        val collection=FriendMusicExport.tasks(page,user,12,playlist)
        assertEquals("34:playlist:-99:8",collection[0].source?.id)
        assertEquals(3,collection[0].folders.size);assertTrue(collection[0].folders.last().startsWith("Плейлист"))
        assertNotEquals(library[0].key,collection[0].key)
        assertEquals(library.map{it.key},FriendMusicExport.tasks(page,user.copy(firstName="Новое имя"),12).map{it.key})
    }
    @Test fun friendsCatalogRetainsSharedPlaylistsAndNeverMixesAlbumsIntoThePlaylistTab()=runTest {
        val playlist=NativePlaylist(-9,1,"Плейлист",1,null,null,null,0,following=false)
        val album=NativePlaylist(-9,2,"Альбом",1,null,null,null,1,following=false)
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=error("unused")
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(listOf(playlist,album),2)
            override suspend fun albumCatalogPage(access: MusicAccess,owner: Long,offset: Int)=NativeCatalog(listOf(album),1)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("unused")
            override suspend fun media(access: MusicAccess,track: NativeTrack)=error("unused")
        }
        val catalog=MusicCatalog.load(api,MusicAccess("a".repeat(64),"CI"),34,personal=false)
        val state=FriendMusicState(tab=FriendMusicTab.PLAYLISTS,catalog=catalog)
        assertEquals(listOf("Плейлист"),state.collections().map{it.title})
        assertEquals(listOf("Альбом"),state.copy(tab=FriendMusicTab.ALBUMS).collections().map{it.title})
        assertEquals(1,state.count(FriendMusicTab.PLAYLISTS));assertEquals(1,state.count(FriendMusicTab.ALBUMS))
        assertNull(FriendMusicState().count(FriendMusicTab.TRACKS))
    }
    @Test fun accessErrorsDoNotInventAnOwnerPrivacySettingAndNeverDisplayRawServerText() {
        val denied=FriendMusicProblem.from(MusicFailure(MusicError.DENIED,15))
        assertTrue(denied.description.contains("подключению"));assertFalse(denied.description.contains("владелец ограничил"))
        assertTrue(FriendMusicProblem.from(MusicFailure(MusicError.METHOD,3)).description.contains("недоступен"))
        assertFalse(FriendMusicProblem.from(IllegalStateException("PRIVATE_TOKEN https://private")).description.contains("PRIVATE"))
    }
}
