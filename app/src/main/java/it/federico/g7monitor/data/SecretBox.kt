package it.federico.g7monitor.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the app's secrets at rest (server tokens, the sensor's authentication keys, the G7
 * certificate) with AES-GCM and a key kept in the Android Keystore: the key never leaves the
 * phone's secure hardware, so a copy of the settings file alone reveals nothing.
 *
 * Stored form: "enc1:" + Base64(IV ‖ ciphertext). Values written by older versions (plain text)
 * are still read, and re-encrypted by [SettingsRepository.encryptOldSecrets].
 */
object SecretBox {
    private const val PREFIX = "enc1:"
    private const val ALIAS = "g7monitor_secrets"
    private const val IV_BYTES = 12

    /** Decrypting goes through the Keystore service: each stored value is decrypted once. */
    private val opened = ConcurrentHashMap<String, String>()

    fun isSealed(stored: String) = stored.startsWith(PREFIX)

    fun seal(plain: String): String {
        if (plain.isEmpty()) return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        opened[sealed] = plain
        return sealed
    }

    /** The plain value; "" when it can no longer be decrypted (Keystore reset): the app asks for it again. */
    fun open(stored: String?): String {
        if (stored.isNullOrEmpty() || !isSealed(stored)) return stored ?: ""
        opened[stored]?.let { return it }
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)).also { opened[stored] = it }
        } catch (e: Exception) {
            DebugLog.log("segreto non leggibile (${e.javaClass.simpleName}): va inserito di nuovo")
            ""
        }
    }

    @Synchronized
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
}
