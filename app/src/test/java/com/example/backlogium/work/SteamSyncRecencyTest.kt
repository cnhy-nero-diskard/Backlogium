package com.example.backlogium.work

import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.lastPlayedAtMillis
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The poll's side of the recency capability: what one observation writes, and what a baseline
 * writes instead.
 *
 * [recencyPollWrite] is pure, so these assert the decision rather than the database. The ordering
 * hazard it exists to contain — that a poll must read the previous last-played time *before* the
 * write that replaces it — shows up here as the function producing the overwrite and the return
 * verdict from one set of pre-poll inputs: given that shape, no caller can get the order wrong
 * without the compiler noticing.
 */
class SteamSyncRecencyTest {

    private val day = 24L * 60 * 60 * 1_000
    private val now = 1_700_000_000_000L

    private fun daysAgo(count: Long) = now - count * day

    @Test
    fun `a new app id on a non-baseline poll is stamped as an arrival`() {
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = true,
            hadPlayIncrease = false,
            storedLastPlayedAt = null,
            mostRecentSessionEndAt = null,
            reportedPlayAt = null,
            observedPlayAt = now,
            now = now,
        )
        assertEquals(now, write.firstSeenAt)
        assertNull(write.returnedToPlayAt)
    }

    @Test
    fun `an already known app id is never stamped as an arrival`() {
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = false,
            storedLastPlayedAt = daysAgo(3),
            mostRecentSessionEndAt = null,
            reportedPlayAt = daysAgo(3),
            observedPlayAt = daysAgo(3),
            now = now,
        )
        assertNull(write.firstSeenAt)
    }

    @Test
    fun `a baseline poll stamps no arrival and records no return but stores last played`() {
        val write = recencyPollWrite(
            isBaseline = true,
            isNewToLibrary = true,
            hadPlayIncrease = true,
            storedLastPlayedAt = daysAgo(400),
            mostRecentSessionEndAt = null,
            reportedPlayAt = daysAgo(1),
            observedPlayAt = daysAgo(1),
            now = now,
        )
        assertNull(write.firstSeenAt)
        assertNull(write.returnedToPlayAt)
        assertEquals(daysAgo(1), write.lastPlayedAt)
    }

    @Test
    fun `a poll over a known library stamps only the app ids it does not recognise`() {
        // The upgrade and post-restore cases, which are the same case: the library is known, so the
        // poll is not a baseline, and every id already stored is left alone while a genuinely new
        // one is stamped.
        val known = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = false,
            storedLastPlayedAt = null,
            mostRecentSessionEndAt = null,
            reportedPlayAt = daysAgo(400),
            observedPlayAt = daysAgo(400),
            now = now,
        )
        val arrival = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = true,
            hadPlayIncrease = false,
            storedLastPlayedAt = null,
            mostRecentSessionEndAt = null,
            reportedPlayAt = null,
            observedPlayAt = now,
            now = now,
        )
        assertNull(known.firstSeenAt)
        assertEquals(now, arrival.firstSeenAt)
        // The known game's last-played time still fills in, which is how an upgrading library gets
        // dates immediately without getting badges.
        assertEquals(daysAgo(400), known.lastPlayedAt)
    }

    @Test
    fun `a poll with no play increase records no return`() {
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = false,
            storedLastPlayedAt = daysAgo(400),
            mostRecentSessionEndAt = null,
            reportedPlayAt = daysAgo(400),
            observedPlayAt = daysAgo(400),
            now = now,
        )
        assertNull(write.returnedToPlayAt)
    }

    @Test
    fun `a poll that advances last played past the threshold still records the return`() {
        // The overwrite and the verdict come out of the same call: `lastPlayedAt` moves to the new
        // value while `returnedToPlayAt` is measured against the old one, which is only possible
        // because both are computed from the pre-poll snapshot.
        val playAt = daysAgo(1)
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = true,
            storedLastPlayedAt = daysAgo(200),
            mostRecentSessionEndAt = null,
            reportedPlayAt = playAt,
            observedPlayAt = playAt,
            now = now,
        )
        assertEquals(playAt, write.lastPlayedAt)
        assertEquals(playAt, write.returnedToPlayAt)
    }

    @Test
    fun `a play increase inside the threshold leaves any stored return untouched`() {
        // Null means "write nothing here"; the update statement's COALESCE is what turns that into
        // "leave the stored value alone".
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = true,
            storedLastPlayedAt = daysAgo(5),
            mostRecentSessionEndAt = daysAgo(5),
            reportedPlayAt = now,
            observedPlayAt = now,
            now = now,
        )
        assertNull(write.returnedToPlayAt)
    }

    @Test
    fun `where Steam reports no last played time the caller's observation instant is used`() {
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = true,
            storedLastPlayedAt = daysAgo(90),
            mostRecentSessionEndAt = null,
            reportedPlayAt = null,
            observedPlayAt = now,
            now = now,
        )
        assertNull(write.lastPlayedAt)
        assertEquals(now, write.returnedToPlayAt)
    }

    @Test
    fun `the commit path derives no play time of its own`() {
        val stored = daysAgo(90)
        fun writeWith(observedPlayAt: Long) = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = true,
            storedLastPlayedAt = stored,
            mostRecentSessionEndAt = null,
            reportedPlayAt = observedPlayAt,
            observedPlayAt = observedPlayAt,
            now = now,
        )
        assertEquals(daysAgo(5), writeWith(daysAgo(5)).returnedToPlayAt)
        assertEquals(daysAgo(1), writeWith(daysAgo(1)).returnedToPlayAt)
    }

    @Test
    fun `a future Steam timestamp cannot record a return in the future`() {
        val write = recencyPollWrite(
            isBaseline = false,
            isNewToLibrary = false,
            hadPlayIncrease = true,
            storedLastPlayedAt = daysAgo(90),
            mostRecentSessionEndAt = null,
            reportedPlayAt = now + 5 * day,
            observedPlayAt = now + 5 * day,
            now = now,
        )
        assertEquals(now, write.returnedToPlayAt)
    }

    @Test
    fun `rtime_last_played of zero reads as unknown`() {
        assertNull(OwnedGameDto(appid = 440L, rtimeLastPlayed = 0).lastPlayedAtMillis)
    }

    @Test
    fun `rtime_last_played converts seconds to millis`() {
        assertEquals(
            1_700_000_000_000L,
            OwnedGameDto(appid = 440L, rtimeLastPlayed = 1_700_000_000L).lastPlayedAtMillis,
        )
    }

    @Test
    fun `an absent rtime_last_played defaults to unknown rather than failing`() {
        assertNull(OwnedGameDto(appid = 440L).lastPlayedAtMillis)
    }

    @Test
    fun `a payload omitting rtime_last_played parses`() {
        // Asserted against the serializer, not just the constructor default: Valve does not
        // document this field, so its absence has to be a parse that succeeds rather than a poll
        // that fails.
        val payload = """
            {"response":{"game_count":1,"games":[
              {"appid":440,"name":"Game","playtime_forever":120}
            ]}}
        """.trimIndent()
        val parsed = steamJson.decodeFromString<OwnedGamesResponse>(payload)
        assertNull(parsed.response.games.single().lastPlayedAtMillis)
    }

    @Test
    fun `a payload carrying rtime_last_played parses it as seconds`() {
        val payload = """
            {"response":{"game_count":1,"games":[
              {"appid":440,"name":"Game","playtime_forever":120,"rtime_last_played":1700000000}
            ]}}
        """.trimIndent()
        val parsed = steamJson.decodeFromString<OwnedGamesResponse>(payload)
        assertEquals(1_700_000_000_000L, parsed.response.games.single().lastPlayedAtMillis)
    }

    /** Mirrors `NetworkModule.provideJson`, so these parse the way the app's own client does. */
    private val steamJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
}
