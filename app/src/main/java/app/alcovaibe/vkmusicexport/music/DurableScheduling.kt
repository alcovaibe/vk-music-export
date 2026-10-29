package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Once QUEUED is committed, cancellation cannot leave the scheduler result unresolved. */
internal suspend fun settleQueueScheduling(schedule: suspend()->Boolean,settled: suspend(Boolean)->Unit) {
    var accepted=false
    try {
        withContext(NonCancellable){accepted=try{schedule()}catch(_: Exception){false}}
    }finally{withContext(NonCancellable){settled(accepted)}}
}
