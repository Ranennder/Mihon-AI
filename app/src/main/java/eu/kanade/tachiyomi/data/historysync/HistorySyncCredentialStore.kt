package eu.kanade.tachiyomi.data.historysync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
enum class HistorySyncProvider { WEBDAV, GOOGLE_DRIVE }

@Serializable
data class HistorySyncCredentials(
    val root: String = "",
    val username: String = "",
    val password: String = "",
    val provider: HistorySyncProvider = HistorySyncProvider.WEBDAV,
    val googleProjectNumber: String = "",
    val googlePermissionId: String = "",
    val googleEmail: String = "",
    val googleDisplayName: String = "",
)

/** Only encrypted bytes live in noBackupFilesDir; the AES key never leaves the
 * Android Keystore. This store is never included in Mihon's native backup. */
class HistorySyncCredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "history-sync-credentials-v1.enc"))

    @Synchronized
    fun load(): HistorySyncCredentials? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 30..32_768 && bytes[0].toInt() == 1) { "Sync credentials need to be entered again" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val decoded = cipher.doFinal(bytes, 13, bytes.size - 13)
        return HistorySyncProtocol.json.decodeFromString<HistorySyncCredentials>(decoded.toString(Charsets.UTF_8))
    }

    @Synchronized
    fun save(credentials: HistorySyncCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = HistorySyncProtocol.json.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 30_000 && cipher.iv.size == 12)
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(encoded)
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "aidoku-mihon-history-sync-v1"
    }
}
