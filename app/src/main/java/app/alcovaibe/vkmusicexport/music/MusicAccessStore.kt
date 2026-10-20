package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent key and noBackup envelope; does not read Chrome, updater access or a site's storage. */
internal class MusicAccessStore(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"native-music-access.bin"))
    private val aad="${context.packageName}:native-music-v1".toByteArray()
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): MusicAccess? = synchronized(lock) {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized null
        val bytes=file.openRead().use{it.readNBytes(16385)}
        musicCheck(bytes.size in 30..16384 && bytes[0]==1.toByte(),MusicError.STORAGE)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)));cipher.updateAAD(aad)
        val o=Json.parseToJsonElement(strictUtf8(cipher.doFinal(bytes.copyOfRange(13,bytes.size)))).jsonObject
        val token=o.getValue("token").jsonPrimitive.content;val agent=o.getValue("agent").jsonPrimitive.content
        musicCheck(NativeProtocol.validAccess(token,agent),MusicError.INVALID_ACCESS)
        MusicAccess(token,agent)
    }
    fun save(access: MusicAccess) = synchronized(lock) {
        musicCheck(NativeProtocol.validAccess(access.token,access.agent),MusicError.INVALID_ACCESS)
        val raw=buildJsonObject{put("token",access.token);put("agent",access.agent)}.toString().toByteArray()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad)
        musicCheck(cipher.iv.size==12,MusicError.STORAGE)
        val bytes=byteArrayOf(1)+cipher.iv+cipher.doFinal(raw)
        val out=file.startWrite()
        try{out.write(bytes);file.finishWrite(out)}catch(e: Exception){file.failWrite(out);throw e}
    }
    fun delete() = synchronized(lock) {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias(ALIAS))deleteEntry(ALIAS)}
    }
    private companion object {const val ALIAS="vkm-native-music-access-v1";val lock=Any()}
}
