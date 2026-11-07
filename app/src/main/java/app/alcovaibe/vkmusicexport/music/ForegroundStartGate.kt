package app.alcovaibe.vkmusicexport.music

/** A pending startForegroundService must be promoted before a rapid stop can tear it down. */
internal class ForegroundStartGate {
    data class Acknowledgement(val cancelled: Boolean,val drained: Boolean)
    private var serial=0L
    private val pending=mutableSetOf<Long>()
    private var cancelled=false
    @Synchronized fun begin(): Long {cancelled=false;return (++serial).also{pending.add(it)}}
    @Synchronized fun stop(): Boolean {cancelled=true;return pending.isEmpty()}
    @Synchronized fun acknowledge(ticket: Long): Acknowledgement {
        pending.remove(ticket)
        return Acknowledgement(cancelled,pending.isEmpty())
    }
    @Synchronized fun pendingCount()=pending.size
}
