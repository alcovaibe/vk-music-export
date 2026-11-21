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
    fun libraryCopy(owner: Long,track: NativeTrack,row: PublishedAudio): Boolean = row.catalogueVerified&&
        row.track.copy(accessKey=null,audioOrigin=null)==track.copy(accessKey=null,audioOrigin=null)&&
        AudioCompleteness.matchesCatalogue(row.durationMs,track.duration)&&
        (if(row.source==null)row.relativePath==ExportLayout.ROOT+"Моя музыка/"
        else row.source==ExportSource(owner,"library","my")&&row.relativePath.startsWith(ExportLayout.ROOT))
    fun success(response: JsonElement) {
        val p=response as? JsonPrimitive
        musicCheck(p!=null&&!p.isString&&p.intOrNull==1,MusicError.RESPONSE)
    }
}

internal enum class LibraryRemovalPhase { IDLE,CONFIRM,SENDING,DONE,ERROR }
internal data class LibraryRemovalState(val visible: Boolean=false,val phase: LibraryRemovalPhase=LibraryRemovalPhase.IDLE,
    val owner: Long=0,val track: NativeTrack?=null,val message: String?=null)
