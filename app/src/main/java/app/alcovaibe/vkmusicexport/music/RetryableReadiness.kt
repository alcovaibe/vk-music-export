package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A failed attempt is retryable; cancelling one waiter does not cancel shared recovery. */
internal class RetryableReadiness(private val scope: CoroutineScope,private val initialize: suspend()->Unit) {
    private var attempt: Deferred<Unit>?=null
    @Synchronized fun deferred(): Deferred<Unit> {
        attempt?.takeUnless{it.isCancelled}?.let{return it}
        return scope.async{initialize()}.also{attempt=it}
    }
}
