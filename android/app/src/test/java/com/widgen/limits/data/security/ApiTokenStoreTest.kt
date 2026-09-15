package com.widgen.limits.data.security

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiTokenStoreTest {

    private class TestTokenStore : ApiTokenStore {
        private var token: String = ""
        override fun get(): String = token
        override fun set(token: String) {
            this.token = token.trim()
        }
        override fun clear() {
            token = ""
        }
    }

    @Test
    fun `store trims and persists token`() {
        val store = TestTokenStore()
        assertEquals("", store.get())

        store.set("   sample_secret_token_123   ")
        assertEquals("sample_secret_token_123", store.get())

        store.clear()
        assertEquals("", store.get())
    }
}
