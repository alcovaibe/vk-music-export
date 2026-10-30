package app.alcovaibe.vkmusicexport.music

import java.io.File

/** Audio is streamed to disk. Its size is limited by available storage, never by a song-size quota. */
internal object AudioStorage {
    private const val RESERVE = 8L * 1024 * 1024
    fun requireSpace(directory: File, bytes: Long = 0, copies: Int = 1) {
        musicCheck(bytes >= 0 && copies > 0 && bytes <= (Long.MAX_VALUE - RESERVE) / copies,
            MusicError.STORAGE)
        musicCheck(directory.usableSpace >= bytes * copies + RESERVE, MusicError.STORAGE)
    }
}
