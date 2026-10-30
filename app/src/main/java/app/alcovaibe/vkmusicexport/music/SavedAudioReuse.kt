package app.alcovaibe.vkmusicexport.music

/** Reconcile known downloads by bytes, not names. Never changes or deletes public audio. */
internal class SavedAudioReuse(private val store: AudioExportStore, private val shared: SharedAudioStore) {
    private val recovery = SavedFileRecovery(shared.context, store, shared)

    suspend fun locate(before: PublishedAudio): PublishedAudio? {
        if (store.existing(before.taskKey) != before) return null
        val check = shared.inspect(before)
        if (check.reason == SavedFileReason.OK) return before
        if (check.reason == SavedFileReason.LOCATION_CHANGED) {
            val actual = check.actual ?: return null
            store.relinked(before, actual)
            return actual
        }
        // Includes moves with a new MediaStore ID and replacements copied from another folder.
        // Only a unique, app-owned copy with the recorded size and SHA-256 is accepted.
        val result = recovery.search(before)
        if (result in setOf(SavedFileReason.AMBIGUOUS, SavedFileReason.IN_USE)) throw SavedFileFailure(result)
        if (result != SavedFileReason.OK) {
            if (check.reason in setOf(SavedFileReason.NO_ACCESS, SavedFileReason.OWNER_CHANGED))
                throw SavedFileFailure(check.reason)
            return null
        }
        return store.existing(before.taskKey)
    }

    suspend fun existing(task: AudioExportTask, queueKeys: Set<String>): PublishedAudio? {
        for (row in store.reusable(task)) {
            val actual = locate(row) ?: continue
            return store.adopt(task, actual, store.existing(task.key))
        }
        // A download made as an album may have been moved over the playlist's file.
        // Reuse that exact known copy in the destination; leave other active positions independent.
        val destination = store.directory(task)
        for (row in store.copies(task.track)) {
            if (row.taskKey != task.key && row.taskKey in queueKeys) continue
            val actual = locate(row) ?: continue
            if (actual.relativePath == destination)
                return store.adopt(task, actual, store.existing(task.key))
        }
        return null
    }
}
