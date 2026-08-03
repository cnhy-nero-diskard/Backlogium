package com.example.backlogium.data.diagnostics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun credentialsAreRemovedWhileEndpointAndAppIdRemain() {
        val identifier = DiagnosticRedaction.requestIdentifier(
            "https://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=secret&steamids=person&appid=620".toHttpUrl(),
        )
        assertFalse(identifier.contains("secret"))
        assertFalse(identifier.contains("person"))
        assertTrue(identifier.contains("GetSchemaForGame"))
        assertTrue(identifier.contains("appid=620"))
    }

    @Test fun urlsWithoutCredentialsRemainLegible() {
        val identifier = DiagnosticRedaction.requestIdentifier("https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/?appid=620".toHttpUrl())
        assertTrue(identifier.contains("appid=620"))
    }
}
