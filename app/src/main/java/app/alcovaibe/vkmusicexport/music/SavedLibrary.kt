package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal enum class SavedAvailability { UNKNOWN,READY,MISSING,CHANGED }
internal data class SavedLibraryEntry(val file: PublishedAudio,val availability: SavedAvailability=SavedAvailability.UNKNOWN,val downloadedOrder: Long=0) {
    constructor(file: PublishedAudio,present: Boolean):this(file,if(present)SavedAvailability.READY else SavedAvailability.MISSING)
    val present get()=availability==SavedAvailability.READY
    val playable get()=availability==SavedAvailability.UNKNOWN||present
}
/** Preserve admission guards for actual file edits; remote metadata reads never own local playback. */
internal object SavedPlaybackPolicy {
    private val remoteReads=setOf("probe","resume_refresh","configure","browser_auth","catalog_refresh","playlist",
        "artists","artist_details","artist_album","availability","remove_inspect")
    fun allowed(state: NativeMusicState)=state.initialized&&(!state.busy||state.stage in remoteReads)
}
internal enum class SavedMusicSort(val title: String) { NEWEST("Сначала скачанные последними"),TITLE("По названию: 0–9, A–Z, А–Я") }
internal object SavedMusicSorting {
    private fun normalized(s: String)=s.trimStart().lowercase(java.util.Locale.ROOT).replace('ё','е')
    private fun group(s: String)=when(s.firstOrNull()){in '0'..'9'->0;in 'a'..'z'->1;in 'а'..'я'->2;null->4;else->3}
    /** Compare digit runs without parsing into bounded integers. Stable for arbitrarily long titles. */
    val titles=Comparator<String>{a,b->
        val x=normalized(a);val y=normalized(b);val groups=group(x).compareTo(group(y))
        if(groups!=0)groups else {
            var i=0;var j=0;var result=0
            while(i<x.length&&j<y.length&&result==0) {
                if(x[i] in '0'..'9'&&y[j] in '0'..'9') {
                    val startI=i;val startJ=j
                    while(i<x.length&&x[i] in '0'..'9')i++
                    while(j<y.length&&y[j] in '0'..'9')j++
                    val left=x.substring(startI,i).trimStart('0');val right=y.substring(startJ,j).trimStart('0')
                    result=left.length.compareTo(right.length).takeIf{it!=0}?:left.compareTo(right)
                }else {result=x[i].compareTo(y[j]);i++;j++}
            }
            if(result!=0)result else (x.length-i).compareTo(y.length-j)
        }
    }
    fun sorted(rows: List<SavedLibraryEntry>,sort: SavedMusicSort)=when(sort) {
        SavedMusicSort.NEWEST->rows.sortedByDescending{it.downloadedOrder}
        SavedMusicSort.TITLE->rows.sortedWith{a,b->titles.compare(a.file.track.displayTitle,b.file.track.displayTitle).takeIf{it!=0}?:b.downloadedOrder.compareTo(a.downloadedOrder)}
    }
}
/** Publish database pages first. MediaStore hints never hold back the list or incremental publications. */
internal class SavedLibrary(private val context: Context,private val scope: CoroutineScope,
    private val presence: suspend(List<PublishedAudio>)->Map<String,SavedAvailability> = {SharedAudioStore(context).presence(it)}) {
    val rows=MutableStateFlow<List<SavedLibraryEntry>>(emptyList())
    val error=MutableStateFlow(false)
    private val lock=Any()
    private val entries=linkedMapOf<String,SavedLibraryEntry>()
    private val checks=linkedMapOf<String,PublishedAudio>()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val updates=Channel<Unit>(Channel.CONFLATED)
    private fun publish(){rows.value=entries.values.sortedByDescending{it.downloadedOrder}}
    private fun merge(changes: List<SavedFileChange>)=synchronized(lock) {
        for(change in changes) {
            val file=change.file
            if(file==null){entries.remove(change.uri);checks.remove(change.uri)}else {
                val old=entries[change.uri]
                entries[change.uri]=SavedLibraryEntry(file,old?.takeIf{it.file==file}?.availability?:SavedAvailability.UNKNOWN,change.order)
                checks[change.uri]=file
            }
        }
        publish();wake.trySend(Unit)
    }
    init {
        scope.launch(Dispatchers.IO) {
            for(signal in wake) {
                while(true) {
                    val batch=synchronized(lock){checks.entries.take(100).map{it.value}.also{rows->rows.forEach{checks.remove(it.uri)}}}
                    if(batch.isEmpty())break
                    val hints=try{presence(batch)}catch(e: CancellationException){throw e}catch(_: Exception){emptyMap()}
                    synchronized(lock) {
                        for(file in batch)entries[file.uri]?.takeIf{it.file==file}?.let{old->
                            entries[file.uri]=old.copy(availability=hints[file.uri]?:SavedAvailability.UNKNOWN)
                        }
                        publish()
                    }
                    yield()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            var revision=0L;var initialized=false
            // A conflated event is safe: all changes after the durable cursor are read, including tombstones.
            launch{SavedLibraryChanges.revision.collect{updates.trySend(Unit)}}
            for(signal in updates)try {
                AudioExportStore(context).use{store->
                    if(!initialized) {
                        revision=store.savedRevision()
                        var before=Long.MAX_VALUE
                        while(true) {
                            ensureActive();val page=store.savedPage(before)
                            if(page.isEmpty())break
                            merge(page);before=page.last().order;yield()
                        }
                        initialized=true
                    }
                    while(true) {
                        ensureActive();val changes=store.savedChanges(revision)
                        if(changes.isEmpty())break
                        merge(changes);revision=changes.last().revision;yield()
                    }
                }
                error.value=false
            }catch(e: CancellationException){throw e}catch(_: Exception){error.value=true}
        }
    }
    fun refresh() {
        // Recheck hints without cancelling a running pass or dropping already displayed files.
        synchronized(lock){entries.values.forEach{checks[it.file.uri]=it.file}}
        wake.trySend(Unit);updates.trySend(Unit)
    }
}
