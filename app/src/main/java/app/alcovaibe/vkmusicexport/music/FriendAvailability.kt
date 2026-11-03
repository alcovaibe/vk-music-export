package app.alcovaibe.vkmusicexport.music

/** Session-scoped, in-memory confirmation cache. Never persists friend identities or tokens to disk. */
internal class FriendAvailabilityCache(
    private val nowMs: ()->Long = System::currentTimeMillis,
    private val lifetimeMs: Long = 15*60_000L
) {
    private data class Entry(val status: FriendAudioAccess, val savedAt: Long)
    private var scope: Pair<String,Long?>? = null
    private val entries=linkedMapOf<Long,Entry>()

    @Synchronized fun activate(readSessionId: String, accountId: Long?) {
        val next=readSessionId to accountId
        if(scope!=next) {entries.clear();scope=next}
    }

    @Synchronized fun clear() {entries.clear();scope=null}

    @Synchronized fun get(friendId: Long): FriendAudioAccess? {
        val entry=entries[friendId]?:return null
        // Time rollback must never prolong a stored permission decision.
        val age=nowMs()-entry.savedAt
        if(age<0||age>=lifetimeMs){entries.remove(friendId);return null}
        return entry.status
    }

    @Synchronized fun put(friendId: Long, status: FriendAudioAccess) {
        if(status==FriendAudioAccess.CHECK_FAILED)return
        if(scope!=null)entries[friendId]=Entry(status,nowMs())
    }

    @Synchronized fun snapshot(friends: List<MusicFriend>): Map<Long,FriendAudioAccess> =
        friends.mapNotNull{friend->get(friend.id)?.let{friend.id to it}}.toMap()
}

/** Only visible items and matches the active search are prioritized; the remaining order is stable. */
internal object FriendAvailabilityPriority {
    fun next(friends: List<MusicFriend>,visibleIds: List<Long>,query: String,
        excluded: (Long)->Boolean): MusicFriend? {
        // Index only the visible subset, not the entire friends list on every preview request.
        if(visibleIds.isNotEmpty()) {
            val visibleSet=visibleIds.toSet()
            val visible=friends.filter{it.id in visibleSet&&!excluded(it.id)}.associateBy{it.id}
            visibleIds.forEach{id->visible[id]?.let{return it}}
        }
        val needle=query.trim()
        if(needle.isNotEmpty()) {
            friends.firstOrNull{friend->
                !excluded(friend.id)&&(friend.name.contains(needle,true)||
                    friend.handle?.contains(needle,true)==true)
            }?.let{return it}
        }
        return friends.firstOrNull{!excluded(it.id)}
    }
}
