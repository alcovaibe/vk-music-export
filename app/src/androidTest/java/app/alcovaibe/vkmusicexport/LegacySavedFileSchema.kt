package app.alcovaibe.vkmusicexport

import android.database.sqlite.SQLiteDatabase

/** Rebuild the exact v1–v4 files layout; API 29 SQLite has no DROP COLUMN support. */
internal fun legacySavedFileSchema(db: SQLiteDatabase) {
    legacyQueueSchema(db)
    db.execSQL("ALTER TABLE files RENAME TO v5_files")
    db.execSQL("CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL)")
    db.execSQL("INSERT INTO files(task_key,track_id,payload) SELECT task_key,track_id,payload FROM v5_files ORDER BY rowid")
    db.execSQL("DROP TABLE v5_files")
    db.execSQL("CREATE INDEX file_tracks ON files(track_id)")
    db.execSQL("DROP TABLE saved_files")
    db.execSQL("DELETE FROM meta WHERE name IN ('saved_revision','saved_order')")
}

/** Retain the v5 saved-file index while emulating pre-v6 queue reads. */
internal fun legacyQueueSchema(db: SQLiteDatabase) {
    for(name in listOf("queue_task_insert","queue_task_update","queue_task_delete","queue_source_insert","queue_source_changed","queue_source_update","queue_source_delete","queue_file_insert","queue_file_update","queue_file_delete"))db.execSQL("DROP TRIGGER IF EXISTS $name")
    db.execSQL("DROP INDEX IF EXISTS tasks_queue_order")
    db.execSQL("DROP TABLE IF EXISTS queue_counts")
    db.execSQL("DELETE FROM meta WHERE name IN ('queue_revision','queue_identity','source_failure_count')")
    // Recreate the independent v5/v6 membership shape, without v7 normalized columns.
    db.execSQL("ALTER TABLE files RENAME TO v7_files")
    db.execSQL("CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL,uri TEXT)")
    db.execSQL("INSERT INTO files(task_key,track_id,payload,uri) SELECT task_key,track_id,payload,uri FROM v7_files ORDER BY rowid")
    db.execSQL("DROP TABLE v7_files")
    db.execSQL("CREATE INDEX file_tracks ON files(track_id)")
    db.execSQL("CREATE INDEX file_uris ON files(uri)")
}
