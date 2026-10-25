package app.alcovaibe.vkmusicexport.updates

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore

/** One-way migration: public updates never read or transmit the old GitHub PAT. Call on IO. */
internal object LegacyUpdateAccess {
    fun cleanup(context: Context) {
        AtomicFile(File(context.noBackupFilesDir,"update-access.bin")).delete()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if(containsAlias("vkm-update-access-v1"))deleteEntry("vkm-update-access-v1")
        }
    }
}
