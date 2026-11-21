package com.example.backlogium.data.remote.dto

import com.example.backlogium.work.shouldPersistOwnedGamesPoll
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.1 decoding/ingestion validation for owned and recently-played responses.
 *
 * An omitted `playtime_forever` must decode as an unreadable reading (null), never as an explicit
 * zero that the diff path would turn into a baseline or a regression. Response envelopes must be
 * confirmed (a present nonnegative count), with an explicit-empty library being exactly
 * `count: 0` + empty list; unconfirmed/private, contradictory and malformed responses must never
 * persist over last-good data. Rows must carry a positive app id and a conflict-free cumulative
 * total to be committed.
 */
class SteamPlaytimeValidationTest {

    /** Mirrors `NetworkModule.provideJson`, so these parse the way the app's own client does. */
    private val steamJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun owned(gamesJson: String) = steamJson.decodeFromString<OwnedGamesResponse>(
        """{"response":{"game_count":1,"games":[$gamesJson]}}""",
    ).response.games.single()

    private fun recent(gamesJson: String) = steamJson.decodeFromString<RecentlyPlayedGamesResponse>(
        """{"response":{"total_count":1,"games":[$gamesJson]}}""",
    ).response.games.single()

    // -------------------------------------------------------------------------------------------
    // Cumulative field presence: omitted / explicit zero / negative / malformed / overflow
    // -------------------------------------------------------------------------------------------

    @Test
    fun `owned omitted playtime_forever decodes as absent, not zero`() {
        val dto = owned("""{"appid":440,"name":"Portal"}""")
        assertNull(dto.playtimeForever)
        assertNull("an omitted cumulative field must never read as a zero baseline", dto.validatedPlaytimeForever)
    }

    @Test
    fun `owned explicit zero playtime_forever is a valid reading`() {
        val dto = owned("""{"appid":440,"name":"Portal","playtime_forever":0}""")
        assertEquals(0, dto.playtimeForever)
        assertEquals("an explicit zero is a genuine cumulative reading", 0, dto.validatedPlaytimeForever)
    }

    @Test
    fun `owned negative playtime_forever is rejected rather than trusted`() {
        val dto = owned("""{"appid":440,"name":"Portal","playtime_forever":-5}""")
        assertEquals(-5, dto.playtimeForever)
        assertNull("a negative count cannot be cumulative playtime", dto.validatedPlaytimeForever)
    }

    @Test
    fun `owned malformed playtime_forever fails to decode into a usable reading`() {
        // A value that cannot be a cumulative integer fails the whole decode under the app's Json
        // config, so the poll never reaches ingestion and last-good data is retained unchanged.
        // (A numeric string like "120" is coerced to 120 by coerceInputValues — pre-existing
        // behaviour, and still a real integer, so it is not treated as malformed here.)
        assertThrows(SerializationException::class.java) {
            owned("""{"appid":440,"name":"Portal","playtime_forever":"abc"}""")
        }
        assertThrows(SerializationException::class.java) {
            owned("""{"appid":440,"name":"Portal","playtime_forever":120.5}""")
        }
    }

    @Test
    fun `owned overflowing playtime_forever never yields a usable reading`() {
        val result = runCatching { owned("""{"appid":440,"name":"Portal","playtime_forever":99999999999999}""") }
        assertNull("an overflowing cumulative value must never authorize a quantity", result.getOrNull()?.validatedPlaytimeForever)
    }

    @Test
    fun `recently played omitted playtime_forever decodes as absent, not zero`() {
        val dto = recent("""{"appid":440,"name":"Portal"}""")
        assertNull(dto.playtimeForever)
        assertNull(dto.validatedPlaytimeForever)
    }

    @Test
    fun `recently played explicit zero playtime_forever is a valid reading`() {
        assertEquals(0, recent("""{"appid":440,"name":"Portal","playtime_forever":0}""").validatedPlaytimeForever)
    }

    @Test
    fun `recently played negative playtime_forever is rejected`() {
        val dto = recent("""{"appid":440,"name":"Portal","playtime_forever":-1}""")
        assertNull(dto.validatedPlaytimeForever)
    }

    @Test
    fun `recently played fraction playtime_forever fails to decode into a usable reading`() {
        assertThrows(SerializationException::class.java) {
            recent("""{"appid":440,"name":"Portal","playtime_forever":120.5}""")
        }
    }

    @Test
    fun `recently played overflowing playtime_forever never yields a usable reading`() {
        val result = runCatching { recent("""{"appid":440,"name":"Portal","playtime_forever":99999999999999}""") }
        assertNull(result.getOrNull()?.validatedPlaytimeForever)
    }

    // -------------------------------------------------------------------------------------------
    // playtime_2weeks: absent is genuine no-play; negative is malformed, never a no-play value
    // -------------------------------------------------------------------------------------------

    @Test
    fun `absent playtime_2weeks means not played recently and stays zero`() {
        assertEquals(0, owned("""{"appid":440,"name":"Portal","playtime_forever":500}""").validatedPlaytime2Weeks)
    }

    @Test
    fun `negative playtime_2weeks is invalid, not a no-play reading`() {
        val dto = owned("""{"appid":440,"name":"Portal","playtime_forever":500,"playtime_2weeks":-3}""")
        assertNull("a negative recent-window count is malformed and must keep stored metadata, not claim no play", dto.validatedPlaytime2Weeks)
    }

    // -------------------------------------------------------------------------------------------
    // Owned envelope confirmation (shouldPersistOwnedGamesPoll)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `private owned envelope does not confirm a library`() {
        val parsed = steamJson.decodeFromString<OwnedGamesResponse>("""{"response":{}}""")
        assertNull(parsed.response.gameCount)
        assertFalse(shouldPersistOwnedGamesPoll(parsed.response.gameCount, parsed.response.games.isEmpty()))
    }

    @Test
    fun `explicit empty owned library is a confirmed successful reading`() {
        val parsed = steamJson.decodeFromString<OwnedGamesResponse>(
            """{"response":{"game_count":0,"games":[]}}""",
        )
        assertEquals(0, parsed.response.gameCount)
        assertTrue(shouldPersistOwnedGamesPoll(parsed.response.gameCount, parsed.response.games.isEmpty()))
    }

    @Test
    fun `positive owned game_count with an empty list is rejected`() {
        assertFalse(shouldPersistOwnedGamesPoll(gameCount = 5, gamesAreEmpty = true))
    }

    @Test
    fun `zero owned game_count with games present is contradictory and rejected`() {
        assertFalse(shouldPersistOwnedGamesPoll(gameCount = 0, gamesAreEmpty = false))
    }

    @Test
    fun `absent owned game_count with games present is unconfirmed and rejected`() {
        assertFalse(shouldPersistOwnedGamesPoll(gameCount = null, gamesAreEmpty = false))
    }

    @Test
    fun `negative owned game_count is rejected whether or not games are present`() {
        assertFalse(shouldPersistOwnedGamesPoll(gameCount = -1, gamesAreEmpty = true))
        assertFalse(shouldPersistOwnedGamesPoll(gameCount = -1, gamesAreEmpty = false))
    }

    @Test
    fun `nonempty owned library with a confirmed positive count persists`() {
        assertTrue(shouldPersistOwnedGamesPoll(gameCount = 5, gamesAreEmpty = false))
    }

    // -------------------------------------------------------------------------------------------
    // Recently-played envelope confirmation (isConfirmedRecentEnvelope)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `private recently played envelope is unconfirmed`() {
        val parsed = steamJson.decodeFromString<RecentlyPlayedGamesResponse>("""{"response":{}}""")
        assertNull(parsed.response.totalCount)
        assertFalse(isConfirmedRecentEnvelope(parsed.response.totalCount, parsed.response.games.isEmpty()))
    }

    @Test
    fun `explicit empty recently played response is confirmed`() {
        val parsed = steamJson.decodeFromString<RecentlyPlayedGamesResponse>(
            """{"response":{"total_count":0,"games":[]}}""",
        )
        assertEquals(0, parsed.response.totalCount)
        assertTrue(isConfirmedRecentEnvelope(parsed.response.totalCount, parsed.response.games.isEmpty()))
    }

    @Test
    fun `positive recent total with no games returned is invalid`() {
        assertFalse(isConfirmedRecentEnvelope(totalCount = 3, gamesAreEmpty = true))
    }

    @Test
    fun `negative recent total_count is unconfirmed`() {
        assertFalse(isConfirmedRecentEnvelope(totalCount = -1, gamesAreEmpty = true))
        assertFalse(isConfirmedRecentEnvelope(totalCount = -1, gamesAreEmpty = false))
    }

    @Test
    fun `bounded recent window with a larger positive total remains confirmed`() {
        // count=20 requested; Steam reports total_count 40 but returns the bounded row subset.
        assertTrue(isConfirmedRecentEnvelope(totalCount = 40, gamesAreEmpty = false))
    }

    // -------------------------------------------------------------------------------------------
    // Per-game commit eligibility: positive identity, usable total, no conflicting duplicates
    // -------------------------------------------------------------------------------------------

    @Test
    fun `validated owned games drops a nonpositive app identity`() {
        val rows = listOf(
            OwnedGameDto(appid = 0L, name = "Broken", playtimeForever = 500),
            OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 600),
        )
        val validated = rows.validatedOwnedGames()
        assertEquals(listOf(440L), validated.map { it.appid })
    }

    @Test
    fun `validated owned games collapses identical duplicate rows`() {
        val rows = listOf(
            OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 500),
            OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 500),
        )
        val validated = rows.validatedOwnedGames()
        assertEquals(listOf(440L), validated.map { it.appid })
        assertEquals(500, validated.single().validatedPlaytimeForever)
    }

    @Test
    fun `validated owned games rejects duplicate rows with conflicting totals`() {
        val rows = listOf(
            OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 500),
            OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 505),
        )
        assertTrue("conflicting readings must not be committed as either value", rows.validatedOwnedGames().isEmpty())
    }

    @Test
    fun `validated owned games drops a game with no usable cumulative total`() {
        val rows = listOf(OwnedGameDto(appid = 440L, name = "Portal"))
        assertTrue(rows.validatedOwnedGames().isEmpty())
    }

    @Test
    fun `validated recent games rejects duplicate rows with conflicting totals`() {
        val rows = listOf(
            RecentlyPlayedGameDto(appid = 440L, name = "Portal", playtimeForever = 130),
            RecentlyPlayedGameDto(appid = 440L, name = "Portal", playtimeForever = 140),
        )
        assertTrue(rows.validatedRecentGames().isEmpty())
    }

    @Test
    fun `an owned game without playtime_forever still carries usable recency fields`() {
        val dto = owned("""{"appid":440,"name":"Portal","rtime_last_played":1700000000}""")
        assertEquals(1_700_000_000_000L, dto.lastPlayedAtMillis)
        assertNull(dto.validatedPlaytimeForever)
    }
}