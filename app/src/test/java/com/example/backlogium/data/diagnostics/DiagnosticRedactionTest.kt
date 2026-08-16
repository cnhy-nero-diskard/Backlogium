package com.example.backlogium.data.diagnostics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun credentialsAreRemovedWhileEndpointAndAppIdRemain() {
        val identifier = DiagnosticRedaction.requestIdentifier(
            "https://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=secret&steamids=person&steamid=person&appid=620".toHttpUrl(),
        )
        assertFalse(identifier.contains("secret"))
        assertFalse(identifier.contains("person"))
        assertFalse(identifier.contains("key"))
        assertFalse(identifier.contains("steamids"))
        assertFalse(identifier.contains("steamid"))
        assertTrue(identifier.contains("GetSchemaForGame"))
        assertTrue(identifier.contains("appid=620"))
    }

    @Test fun urlsWithoutCredentialsRemainLegible() {
        val identifier = DiagnosticRedaction.requestIdentifier("https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/?appid=620".toHttpUrl())
        assertTrue(identifier.contains("appid=620"))
    }

    @Test fun unknownParameterValuesAreDroppedButEndpointRemainsAttributable() {
        val identifier = DiagnosticRedaction.requestIdentifier(
            "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?unknown=secret&format=json".toHttpUrl(),
        )

        assertTrue(identifier.startsWith("/ISteamUser/GetPlayerSummaries/v2/"))
        assertTrue(identifier.contains("format=json"))
        assertFalse(identifier.contains("unknown"))
        assertFalse(identifier.contains("secret"))
    }

    @Test fun differentSteamIdsProduceTheSameIdentifier() {
        val first = DiagnosticRedaction.requestIdentifier(
            "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?steamid=76561198000000001&appid=620".toHttpUrl(),
        )
        val second = DiagnosticRedaction.requestIdentifier(
            "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?steamid=76561198000000002&appid=620".toHttpUrl(),
        )

        assertEquals(first, second)
    }
}
