package cc.cherr.codexdeck.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(context: String, plaintext: ByteArray): ByteArray
    fun decrypt(context: String, blob: ByteArray): ByteArray
}

/**
 * AES-GCM keys stay in Android Keystore. A missing Keystore key is never silently recreated,
 * because that would make existing ciphertext look valid while being undecryptable.
 */
class CredentialVault(
    private val alias: String = "codexdeck.credentials.v1",
) : SecretCipher {
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "设备加密密钥不可用，请重新导入凭据" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    @Synchronized
    override fun encrypt(context: String, plaintext: ByteArray): ByteArray =
        SecretEnvelope.encrypt(key(create = true), context, plaintext)

    @Synchronized
    override fun decrypt(context: String, blob: ByteArray): ByteArray =
        SecretEnvelope.decrypt(key(create = false), context, blob)
}

internal object SecretEnvelope {
    private const val VERSION: Byte = 1
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, context: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("codexdeck:v1:$context".toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == IV_SIZE)
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, context: String, blob: ByteArray): ByteArray {
        require(blob.size >= 1 + IV_SIZE + TAG_BITS / 8 && blob[0] == VERSION) {
            "不支持的凭据格式"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_SIZE)),
        )
        cipher.updateAAD("codexdeck:v1:$context".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, 1 + IV_SIZE, blob.size - 1 - IV_SIZE)
    }
}
