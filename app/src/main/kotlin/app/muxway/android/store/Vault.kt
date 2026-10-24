package app.muxway.android.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One token per hub, each encrypted with a key that never leaves the Android Keystore.
 * Only the ciphertext is in the app's preferences; backup is off.
 */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun hasToken(id: String): Boolean {
        adopt(id)
        return prefs.contains(key(id))
    }

    fun token(id: String): String? {
        adopt(id)
        val raw = prefs.getString(key(id), null) ?: return null
        return try {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            // A reset keystore leaves ciphertext nothing can open; start over.
            clear(id)
            null
        }
    }

    fun store(id: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + data
        prefs.edit().putString(key(id), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun clear(id: String) {
        prefs.edit().remove(key(id)).apply()
    }

    private fun key(id: String) = "$TOKEN:$id"

    // The single token of an older install belongs to the hub the settings gave the legacy id.
    private fun adopt(id: String) {
        if (id != Hubs.LEGACY_ID || !prefs.contains(TOKEN)) return
        prefs.edit().putString(key(id), prefs.getString(TOKEN, null)).remove(TOKEN).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "muxway.token"
        const val TOKEN = "token"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
