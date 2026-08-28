package com.rork.mindsetframestracker.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals short secrets (Supabase session tokens) with an AES-256-GCM key held
 * in the Android Keystore. The key is generated inside — and never leaves —
 * the device's secure hardware, so sealed values stored in SharedPreferences
 * are useless off-device (rooted file pulls, backup extraction, device
 * transfer) and to any other process on-device.
 *
 * Sealed format: `v1:<base64 iv>:<base64 ciphertext+tag>`.
 */
object TokenCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mf_token_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "v1:"
    private const val TAG = "TokenCipher"

    /** True when [stored] is already a sealed value (vs. legacy plaintext). */
    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /**
     * Encrypts [plain] with the Keystore key. On the extremely rare devices
     * with a broken keystore this degrades to storing the raw value (same as
     * pre-hardening behavior) rather than breaking sign-in entirely.
     */
    fun seal(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Keystore unavailable — storing token unsealed: ${e.javaClass.simpleName}")
        plain
    }

    /**
     * Decrypts a [seal]ed value. Legacy plaintext values (no `v1:` prefix)
     * are returned as-is so sessions from older app versions keep working.
     * Returns null when a sealed value can no longer be decrypted (e.g. the
     * keystore was reset), signalling the caller to drop the session.
     */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            require(parts.size == 2) { "Malformed sealed token" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unseal stored token: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Returns the existing Keystore key or generates it on first use. */
    @Synchronized
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
