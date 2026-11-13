package com.hangge.rootpilot.data.prefs

import java.security.SecureRandom

/**
 * Owns the Agent bearer token. The plaintext token is never written to SharedPreferences, Room,
 * exports, the UI (beyond a mask), or logcat.
 */
class TokenStore(
    private val store: KeyValueStore,
    private val cipher: TokenCipher,
    private val generator: () -> String = ::randomToken,
) {

    /** Returns the stored token, creating one on first use or after keystore invalidation. */
    fun token(): String {
        val stored = load()
        if (stored != null) return stored
        return regenerate()
    }

    fun regenerate(): String {
        val fresh = generator()
        val encrypted = cipher.encrypt(fresh)
        store.putString(KEY_IV, encrypted.ivBase64)
        store.putString(KEY_CIPHER, encrypted.cipherBase64)
        return fresh
    }

    /** Display form: first four and last four characters, never the whole secret. */
    fun masked(): String {
        val token = token()
        if (token.length <= 8) return "•".repeat(token.length.coerceAtLeast(8))
        return "${token.take(4)}••••${token.takeLast(4)}"
    }

    private fun load(): String? {
        val iv = store.getString(KEY_IV, null) ?: return null
        val cipherText = store.getString(KEY_CIPHER, null) ?: return null
        return runCatching { cipher.decrypt(EncryptedToken(iv, cipherText)) }.getOrNull()
    }

    companion object {
        const val KEY_IV = "token_iv"
        const val KEY_CIPHER = "token_cipher"

        fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
