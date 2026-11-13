package com.hangge.rootpilot.data.prefs

import com.hangge.rootpilot.testing.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {

    private class FakeCipher : TokenCipher {
        var failures = 0

        override fun encrypt(plainText: String): EncryptedToken =
            EncryptedToken(ivBase64 = "iv", cipherBase64 = "cipher:$plainText")

        override fun decrypt(token: EncryptedToken): String {
            if (failures > 0) {
                failures--
                error("keystore key invalidated")
            }
            return token.cipherBase64.removePrefix("cipher:")
        }
    }

    @Test
    fun `first read creates and persists an encrypted token`() {
        val backing = InMemoryKeyValueStore()
        val store = TokenStore(backing, FakeCipher()) { "token-one" }

        assertEquals("token-one", store.token())
        assertEquals("iv", backing.raw(TokenStore.KEY_IV))
        assertEquals("cipher:token-one", backing.raw(TokenStore.KEY_CIPHER))
        assertFalse("plaintext token must never be stored", backing.raw(TokenStore.KEY_CIPHER) == "token-one")
    }

    @Test
    fun `existing token is reused across instances`() {
        val backing = InMemoryKeyValueStore()
        TokenStore(backing, FakeCipher()) { "stable-token" }.token()

        val reopened = TokenStore(backing, FakeCipher()) { "should-not-be-used" }
        assertEquals("stable-token", reopened.token())
    }

    @Test
    fun `regenerate replaces the persisted token`() {
        val backing = InMemoryKeyValueStore()
        val store = TokenStore(backing, FakeCipher()) { "fresh-token" }

        assertEquals("fresh-token", store.regenerate())
        assertEquals("fresh-token", store.token())
        assertEquals("cipher:fresh-token", backing.raw(TokenStore.KEY_CIPHER))
    }

    @Test
    fun `masked never exposes the middle of the token`() {
        val store = TokenStore(InMemoryKeyValueStore(), FakeCipher()) { "abcdefghijklmnop" }

        val masked = store.masked()
        assertTrue(masked.startsWith("abcd"))
        assertTrue(masked.endsWith("mnop"))
        assertTrue(masked.contains("••••"))
        assertFalse(masked.contains("efghijkl"))
    }

    @Test
    fun `undecryptable material regenerates instead of crashing`() {
        val backing = InMemoryKeyValueStore()
        backing.putString(TokenStore.KEY_IV, "iv")
        backing.putString(TokenStore.KEY_CIPHER, "cipher:stale")
        val cipher = FakeCipher().apply { failures = 1 }
        val store = TokenStore(backing, cipher) { "brand-new" }

        assertEquals("brand-new", store.token())
        assertEquals("cipher:brand-new", backing.raw(TokenStore.KEY_CIPHER))
    }
}
