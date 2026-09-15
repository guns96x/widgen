package com.widgen.limits.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeUrlValidatorTest {

    @Test
    fun `empty or blank input fails`() {
        val res1 = BridgeUrlValidator.normalize("")
        assertTrue(res1.isFailure)

        val res2 = BridgeUrlValidator.normalize("   ")
        assertTrue(res2.isFailure)
    }

    @Test
    fun `auto-prepends http if scheme missing`() {
        val res = BridgeUrlValidator.normalize("192.168.1.50:59123")
        assertTrue(res.isSuccess)
        assertEquals("http://192.168.1.50:59123", res.getOrNull())
    }

    @Test
    fun `keeps https if specified`() {
        val res = BridgeUrlValidator.normalize("https://mybridge.tailscale.net:59123")
        assertTrue(res.isSuccess)
        assertEquals("https://mybridge.tailscale.net:59123", res.getOrNull())
    }

    @Test
    fun `strips trailing slashes`() {
        val res = BridgeUrlValidator.normalize("http://100.82.252.86:59123/")
        assertTrue(res.isSuccess)
        assertEquals("http://100.82.252.86:59123", res.getOrNull())
    }

    @Test
    fun `rejects dangerous schemes`() {
        val fileRes = BridgeUrlValidator.normalize("file:///etc/passwd")
        assertTrue(fileRes.isFailure)

        val jsRes = BridgeUrlValidator.normalize("javascript:alert(1)")
        assertTrue(jsRes.isFailure)

        val contentRes = BridgeUrlValidator.normalize("content://media/external")
        assertTrue(contentRes.isFailure)
    }

    @Test
    fun `rejects malformed URLs without host`() {
        val res = BridgeUrlValidator.normalize("http://:59123")
        assertTrue(res.isFailure)
    }
}
