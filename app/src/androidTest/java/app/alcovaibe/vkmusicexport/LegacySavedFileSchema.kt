package app.alcovaibe.vkmusicexport

import android.database.sqlite.SQLiteDatabase

/** Rebuild the exact v1–v4 files layout; API 29 SQLite has no DROP COLUMN support. */
internal fun legacySavedFileSchema(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE files RENAME TO v5_files")
    db.execSQL("CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL)")
    db.execSQL("INSERT INTO files(task_key,track_id,payload) SELECT task_key,track_id,payload FROM v5_files ORDER BY rowid")
    db.execSQL("DROP TABLE v5_files")
    db.execSQL("CREATE INDEX file_tracks ON files(track_id)")
    db.execSQL("DROP TABLE saved_files")
    db.execSQL("DELETE FROM meta WHERE name IN ('saved_revision','saved_order')")
}
