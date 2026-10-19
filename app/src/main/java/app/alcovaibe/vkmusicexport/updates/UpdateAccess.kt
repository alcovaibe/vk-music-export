package app.alcovaibe.vkmusicexport.updates

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface UpdateAccess {
    fun read(): String?
    fun save(token: String)
    fun delete()
}

/** Call on IO. Only the encrypted envelope is stored, excluded from backup. Never stringify secrets. */
internal class KeystoreUpdateAccess(context: Context) : UpdateAccess {
    private val file=AtomicFile(File(context.noBackupFilesDir,"update-access.bin"))
    private val alias="vkm-update-access-v1"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): String? = synchronized(lock){readLocked()}
    private fun readLocked(): String? {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return null
        val bytes=file.openRead().use{input->input.readNBytes(1025)}
        check(bytes.size in 30..1024 && bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(UpdateProtocol.PACKAGE.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(13,bytes.size)).toString(Charsets.UTF_8).also{check(UpdateProtocol.validToken(it))}
    }
    override fun save(token: String) = synchronized(lock){saveLocked(token)}
    private fun saveLocked(token: String) {
        require(UpdateProtocol.validToken(token))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        cipher.updateAAD(UpdateProtocol.PACKAGE.toByteArray())
        check(cipher.iv.size==12)
        val bytes=byteArrayOf(1)+cipher.iv+cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val out=file.startWrite()
        try {out.write(bytes);file.finishWrite(out)}catch(e: Exception){file.failWrite(out);throw e}
    }
    override fun delete() = synchronized(lock){deleteLocked()}
    private fun deleteLocked() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias(alias))deleteEntry(alias)}
    }
    private companion object {val lock=Any()}
}
