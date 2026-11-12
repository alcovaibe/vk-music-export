package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Only a verified, complete friend list can be persisted. Audio permissions, tokens,
 * signed music URLs and session-scoped availability decisions must never go here.
 */
internal data class FriendListSnapshot(val accountId: Long,val updatedAtMs: Long,val friends: List<MusicFriend>)

internal object FriendListRefreshPolicy {
    const val FRESH_MS=6*60*60_000L
    const val RETRY_MS=15*60_000L
    fun fresh(updatedAtMs: Long?,nowMs: Long): Boolean {
        if(updatedAtMs==null||updatedAtMs<=0||nowMs<updatedAtMs)return false
        return nowMs-updatedAtMs<FRESH_MS
    }
    fun retryAllowed(lastAttemptMs: Long?,nowMs: Long): Boolean {
        if(lastAttemptMs==null||lastAttemptMs<=0||nowMs<lastAttemptMs)return true
        return nowMs-lastAttemptMs>=RETRY_MS
    }
}

@Serializable
private data class FriendCacheRow(
    val id: Long,val firstName: String,val lastName: String,
    val handle: String?=null,val photo: String?=null,
    val closed: Boolean=false,val accessible: Boolean=true
) {
    fun toFriend()=MusicFriend(id,firstName,lastName,handle,photo,closed,accessible)
    companion object {
        fun from(friend: MusicFriend)=FriendCacheRow(friend.id,friend.firstName,friend.lastName,
            friend.handle,friend.photo?.takeIf{it.length<=2048&&it.startsWith("https://")},
            friend.closed,friend.accessible)
    }
}

@Serializable
private data class FriendCacheEnvelope(
    val version: Int=1,val accountId: Long,val updatedAtMs: Long,
    val total: Int,val rows: List<FriendCacheRow>
)

/**
 * Encrypted and atomically replaced private snapshot. Saved under noBackupFilesDir:
 * neither Android Backup nor a process recreation should expose/reload another account.
 * The independent Keystore key is intentionally never shared with the VK access token.
 */
internal class FriendListCacheStore(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"vk-friends-list-v1.bin"))
    private val aad="${context.packageName}:vk-friends-list-v1".toByteArray(Charsets.UTF_8)
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=false }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(KEY_ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    /** Wrong-account or corrupted cache is not eligible for even momentary presentation. */
    fun read(accountId: Long): FriendListSnapshot?=synchronized(lock) {
        require(accountId>0)
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized null
        try {
            val bytes=file.openRead().use{it.readAtMost(MAX_BYTES+1)}
            require(bytes.size in 30..MAX_BYTES&&bytes[0]==1.toByte())
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
            cipher.updateAAD(aad)
            val raw=cipher.doFinal(bytes.copyOfRange(13,bytes.size))
            val envelope=json.decodeFromString<FriendCacheEnvelope>(raw.toString(Charsets.UTF_8))
            require(envelope.version==1&&envelope.accountId>0&&envelope.updatedAtMs>0)
            require(envelope.rows.size==envelope.total&&envelope.total in 0..MAX_FRIENDS)
            require(envelope.rows.all { row ->
                row.id>0&&row.firstName.length<=300&&row.lastName.length<=300&&
                    (row.firstName.isNotBlank()||row.lastName.isNotBlank())&&
                    row.handle.orEmpty().length<=102&&
                    (row.photo==null||row.photo.length<=2048&&row.photo.startsWith("https://"))
            })
            require(envelope.rows.map{it.id}.toSet().size==envelope.total)
            if(envelope.accountId!=accountId) {
                // Only one account's snapshot is retained on this device.
                file.delete()
                return@synchronized null
            }
            FriendListSnapshot(envelope.accountId,envelope.updatedAtMs,
                envelope.rows.map{it.toFriend()})
        }catch(_: Exception) {
            file.delete()
            null
        }
    }

    /** Must be called on Dispatchers.IO, after VK has supplied all verified pages. */
    fun write(snapshot: FriendListSnapshot)=synchronized(lock) {
        require(snapshot.accountId>0&&snapshot.updatedAtMs>0)
        require(snapshot.friends.size<=MAX_FRIENDS&&
            snapshot.friends.map{it.id}.toSet().size==snapshot.friends.size)
        val envelope=FriendCacheEnvelope(accountId=snapshot.accountId,
            updatedAtMs=snapshot.updatedAtMs,total=snapshot.friends.size,
            rows=snapshot.friends.map(FriendCacheRow::from))
        val raw=json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        require(raw.size<=MAX_BYTES-29) { "Friends snapshot exceeds storage limit" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        require(cipher.iv.size==12)
        cipher.updateAAD(aad)
        val bytes=byteArrayOf(1)+cipher.iv+cipher.doFinal(raw)
        val out=file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        }catch(e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun clear()=synchronized(lock) { file.delete() }

    private companion object {
        const val MAX_FRIENDS=20_000
        const val MAX_BYTES=48*1024*1024
        const val KEY_ALIAS="vkm-friends-cache-v1"
        val lock=Any()
    }
}

/** IO boundary shared by the UI controller and future background synchronization. */
internal class FriendListRepository(private val store: FriendListCacheStore) {
    suspend fun restore(accountId: Long): FriendListSnapshot?=
        withContext(Dispatchers.IO) { store.read(accountId) }
    suspend fun persist(snapshot: FriendListSnapshot)=
        withContext(Dispatchers.IO) { store.write(snapshot) }
    suspend fun clear()=withContext(Dispatchers.IO) { store.clear() }
}
