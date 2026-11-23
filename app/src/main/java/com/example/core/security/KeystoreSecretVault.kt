package com.example.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android Keystore-backed AES-256-GCM vault for encrypting sensitive API keys & tokens.
 * Never logs or exposes plaintext keys outside active memory requests.
 */
class KeystoreSecretVault(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "teena_ai_master_vault_key_v1"
        private const val PREFS_NAME = "teena_secure_vault_prefs"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_SIZE = 12
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val isHardwareKeystoreAvailable: Boolean by lazy {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            true
        }.getOrDefault(false)
    }

    private fun getOrCreateKey(): SecretKey {
        return if (isHardwareKeystoreAvailable) {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } else {
            // Deterministic fallback ONLY for non-Keystore JVM test runners (e.g. Robolectric)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${context.packageName}:$KEY_ALIAS".toByteArray(StandardCharsets.UTF_8))
            SecretKeySpec(digest, "AES")
        }
    }

    fun saveSecret(slotId: String, plaintext: String) {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) {
            deleteSecret(slotId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString("enc_$slotId", encoded).commit()
    }

    fun readSecret(slotId: String): String? {
        val encoded = prefs.getString("enc_$slotId", null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_SIZE) return null
            val iv = combined.copyOfRange(0, IV_SIZE)
            val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun deleteSecret(slotId: String) {
        prefs.edit().remove("enc_$slotId").commit()
    }

    fun hasSecret(slotId: String): Boolean = !readSecret(slotId).isNullOrBlank()

    fun maskedPreview(slotId: String): String {
        val raw = readSecret(slotId) ?: return "Not configured"
        return if (raw.length <= 8) "••••••••" else "${raw.take(4)}••••${raw.takeLast(4)}"
    }
}
