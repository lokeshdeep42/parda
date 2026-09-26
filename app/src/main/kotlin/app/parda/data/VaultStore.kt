package app.parda.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.parda.core.disclosure.Vault
import app.parda.core.disclosure.VaultArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vaults of recent hand-backs, so a reply that comes back hours later still reads with real
 * names. Stored in the app's private files, encrypted with an AES key that Android Keystore holds
 * and never hands out, kept for a day, and gone at once on "Forget".
 */
class VaultStore(context: Context) {
    private val file = File(context.filesDir, "vault.bin")
    private val _archive = MutableStateFlow(read())
    val archive: StateFlow<VaultArchive> = _archive.asStateFlow()

    @Synchronized
    fun remember(title: String, vault: Vault) = save(_archive.value.add(title, vault, System.currentTimeMillis()))

    @Synchronized
    fun forget(id: Long) = save(_archive.value.without(id))

    @Synchronized
    fun forgetAll() = save(VaultArchive())

    /** Drops what has expired. */
    @Synchronized
    fun refresh() = save(_archive.value.expire(System.currentTimeMillis()))

    private fun save(archive: VaultArchive) {
        _archive.value = archive
        if (archive.handBacks.isEmpty()) {
            file.delete()
            return
        }
        runCatching { file.writeBytes(encrypt(archive.toJson().toByteArray())) }
    }

    private fun read(): VaultArchive =
        runCatching { VaultArchive.fromJson(String(decrypt(file.readBytes()))) }
            .getOrDefault(VaultArchive())
            .expire(System.currentTimeMillis())

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    /** The random IV Keystore picks, then the ciphertext and its tag. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES)) }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "parda-vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
