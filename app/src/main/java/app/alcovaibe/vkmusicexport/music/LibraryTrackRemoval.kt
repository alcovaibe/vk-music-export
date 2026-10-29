package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal object LibraryRemovalProtocol {
    fun audioId(owner: Long,track: NativeTrack): Long {
        val ids=Regex("([1-9][0-9]*)_([1-9][0-9]*)").matchEntire(track.id)?:throw MusicFailure(MusicError.ACCESS)
        musicCheck(owner>0&&ids.groupValues[1].toLongOrNull()==owner,MusicError.ACCESS)
        return ids.groupValues[2].toLongOrNull()?.takeIf{it>0}?:throw MusicFailure(MusicError.ACCESS)
    }
    fun record(owner: Long,track: NativeTrack,row: PublishedAudio) {
        audioId(owner,track)
        musicCheck(libraryCopy(owner,track,row),MusicError.INCOMPLETE)
    }
    fun libraryCopy(owner: Long,track: NativeTrack,row: PublishedAudio): Boolean = row.track==track&&
        AudioCompleteness.matchesCatalogue(row.durationMs,track.duration)&&
        (if(row.source==null)row.relativePath==ExportLayout.ROOT+"Моя музыка/"
        else row.source==ExportSource(owner,"library","my")&&row.relativePath.startsWith(ExportLayout.ROOT))
    fun success(response: JsonElement) {
        val p=response as? JsonPrimitive
        musicCheck(p!=null&&!p.isString&&p.intOrNull==1,MusicError.RESPONSE)
    }
}

/** This service has no local deletion operation. Recheck the preserved copy before the single mutation. */
internal class LibraryTrackRemoval(private val api: MusicApi,private val valid: suspend(PublishedAudio)->Boolean) {
    suspend fun remove(access: MusicAccess,owner: Long,track: NativeTrack,row: PublishedAudio,beforeSend: suspend()->Unit={}) {
        LibraryRemovalProtocol.record(owner,track,row)
        musicCheck(valid(row),MusicError.INCOMPLETE)
        beforeSend()
        api.removeLibraryTrack(access,owner,track)
    }
}

internal enum class LibraryRemovalPhase { IDLE,CHECKING,CONFIRM,SENDING,DONE,ERROR }
internal data class LibraryRemovalState(val visible: Boolean=false,val phase: LibraryRemovalPhase=LibraryRemovalPhase.IDLE,
    val owner: Long=0,val track: NativeTrack?=null,val file: PublishedAudio?=null,val message: String?=null)
