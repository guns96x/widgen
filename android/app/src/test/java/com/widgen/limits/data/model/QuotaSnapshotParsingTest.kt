package com.widgen.limits.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaSnapshotParsingTest {

    private val gson = Gson()

    @Test
    fun `parse offline snapshot with null fields`() {
        val json = """
            {
                "status": "offline",
                "updatedAt": "2026-09-15T20:00:00Z",
                "message": "Bridge cannot reach providers"
            }
        """.trimIndent()

        val snapshot = gson.fromJson(json, QuotaSnapshot::class.java)
        assertEquals("offline", snapshot.status)
        assertFalse(snapshot.isOnline)
        assertNull(snapshot.codex)
        assertNull(snapshot.antigravity)
        assertEquals("Bridge cannot reach providers", snapshot.message)
    }

    @Test
    fun `parse full multi-account snapshot`() {
        val json = """
            {
                "status": "online",
                "updatedAt": "2026-09-15T20:30:00Z",
                "codex": {
                    "status": "online",
                    "email": "user@openai.com",
                    "plan": "Plus",
                    "sessionWindow": {
                        "remainingPercent": 85,
                        "usedPercent": 15,
                        "resetFormatted": "4h 12m"
                    },
                    "weeklyWindow": {
                        "remainingPercent": 30,
                        "usedPercent": 70,
                        "resetFormatted": "2d 5h"
                    },
                    "resetCredits": 1
                },
                "antigravity": {
                    "status": "online",
                    "accounts": [
                        {
                            "id": "acc-1",
                            "email": "primary@gmail.com",
                            "name": "Dev Account",
                            "isCurrent": true,
                            "geminiPercent": 90,
                            "geminiReset": "Ready",
                            "claudePercent": 100,
                            "claudeReset": "Ready"
                        }
                    ]
                }
            }
        """.trimIndent()

        val snapshot = gson.fromJson(json, QuotaSnapshot::class.java)
        assertTrue(snapshot.isOnline)
        assertNotNull(snapshot.codex)
        assertEquals(85, snapshot.codex?.sessionWindow?.remainingPercent)
        assertEquals(30, snapshot.codex?.weeklyWindow?.remainingPercent)
        assertEquals(1, snapshot.codex?.resetCredits)

        assertNotNull(snapshot.antigravity)
        assertEquals(1, snapshot.antigravity?.accounts?.size)
        val acc = snapshot.antigravity?.accounts?.first()
        assertEquals("acc-1", acc?.id)
        assertTrue(acc?.isCurrent == true)
        assertEquals(90, acc?.geminiPercent)
    }
}
