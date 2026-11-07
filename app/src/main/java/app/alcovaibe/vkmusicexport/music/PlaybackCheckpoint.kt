package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable

@Serializable internal data class PlaybackCursor(val index: Int,val positionMs: Long=0,val shuffle: Boolean=false,val repeat: Int=0)
internal data class PlaybackCheckpoint(val id: String,val entries: List<PlaybackEntry>,val session: String?,
    val cursor: PlaybackCursor,val order: List<Int>) {
    fun validate() {
        require(id.isNotBlank()&&id.length<=100&&entries.size in 1..100_000)
        require(cursor.index in entries.indices&&cursor.positionMs>=0&&cursor.repeat in 0..2)
        require(order.size==entries.size&&order.toSet()==entries.indices.toSet())
        val online=entries.filterNot{it.offline}
        require(online.isEmpty()&&session==null||online.isNotEmpty()&&!session.isNullOrBlank())
        require(online.all{(it.account?:0)>0&&(it.readSessionId==null||it.readSessionId==session)})
        require(online.map{it.account}.distinct().size<=1)
        require(entries.all{it.track.id.isNotBlank()&&it.saved?.track?.id.let{id->id==null||id==it.track.id}})
    }
    fun accepts(access: MusicAccess?)=entries.all{it.offline}||access!=null&&access.readSessionId==session&&
        entries.filterNot{it.offline}.all{access.accountId==null||it.account==access.accountId}
    fun adjacent(direction: Int): Int? {
        val sequence=if(cursor.shuffle)order else entries.indices.toList()
        val at=sequence.indexOf(cursor.index)+direction
        return sequence.getOrNull(at)?:if(cursor.repeat==2)sequence[if(direction>0)0 else sequence.lastIndex]else null
    }
    fun pausedState(): PlaybackState {
        val current=entries[cursor.index]
        return PlaybackState(entries=entries,index=cursor.index,positionMs=cursor.positionMs,
            durationMs=(current.saved?.durationMs?:current.track.duration.toLong()*1000).coerceAtLeast(0),seekable=true,
            shuffle=cursor.shuffle,repeat=cursor.repeat,order=if(cursor.shuffle)order else entries.indices.toList(),
            previous=cursor.positionMs>3000||adjacent(-1)!=null,next=adjacent(1)!=null)
    }
    override fun toString()="PlaybackCheckpoint(count=${entries.size}, index=${cursor.index})"
}
