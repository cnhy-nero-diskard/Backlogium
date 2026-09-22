package com.example.backlogium.ui.home

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.domain.GameSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNextActionTest {

    @Test
    fun `state inputs recalculate when focus completion collection order or live status changes`() {
        val focus = game(1L, "Focus", playtime = 10, completion = 100, lastPlayed = 100L)
        val queue = card(2L, "Queue", CollectionMode.ORDERED_QUEUE, gameId = 22L)

        assertEquals(
            HomeNextAction.ContinueFocus(HomeNextGame(1L, "Focus", "icon-1")),
            nextActionForHomeState(listOf(focus), listOf(queue), NowPlaying.NotPlaying),
        )
        assertEquals(
            HomeNextAction.ContinueCollection(2L, "Queue", HomeNextGame(22L, "Game 22", "icon-22")),
            nextActionForHomeState(
                focusGames = listOf(focus.copy(playtimeForever = 100)),
                collections = listOf(queue),
                nowPlaying = NowPlaying.NotPlaying,
            ),
        )
        assertEquals(
            HomeNextAction.ContinueCollection(2L, "Queue", HomeNextGame(22L, "Game 22", "icon-22")),
            nextActionForHomeState(
                focusGames = emptyList(),
                collections = listOf(queue),
                nowPlaying = NowPlaying.InGame(99L, "Running", null),
            ),
        )
        assertEquals(
            HomeNextAction.ChooseGame,
            nextActionForHomeState(
                focusGames = emptyList(),
                collections = listOf(queue),
                nowPlaying = NowPlaying.InGame(22L, "Queue game", null),
            ),
        )
    }

    @Test
    fun `most recently played incomplete Focus game wins`() {
        val action = selectHomeNextAction(
            focusGames = listOf(
                game(1L, "Completed", playtime = 100, completion = 100, lastPlayed = 300L),
                game(2L, "Older", playtime = 10, completion = 100, lastPlayed = 100L),
                game(3L, "Recent", playtime = 20, completion = 100, lastPlayed = 200L),
            ),
            collections = emptyList(),
        )

        assertEquals(
            HomeNextAction.ContinueFocus(HomeNextGame(3L, "Recent", "icon-3")),
            action,
        )
    }

    @Test
    fun `recently played shared Focus game outranks stale owned Focus game`() {
        val shared = game(2L, "Shared", playtime = 0, completion = 100)
            .copy(source = GameSource.FAMILY_SHARED)
        val action = selectHomeNextAction(
            focusGames = listOf(
                game(1L, "Owned", playtime = 10, completion = 100, lastPlayed = 300L),
                shared,
            ),
            collections = emptyList(),
            trackedMinutesByGame = mapOf(2L to 30),
            latestSessionAtByGame = mapOf(2L to 400L),
        )

        assertEquals(
            HomeNextAction.ContinueFocus(HomeNextGame(2L, "Shared", "icon-2")),
            action,
        )
    }

    @Test
    fun `unknown completion length remains an eligible Focus game`() {
        val action = selectHomeNextAction(
            focusGames = listOf(game(1L, "Unmatched", playtime = 300, completion = null)),
            collections = emptyList(),
        )

        assertEquals(
            HomeNextAction.ContinueFocus(HomeNextGame(1L, "Unmatched", "icon-1")),
            action,
        )
    }

    @Test
    fun `ordered queue fallback skips non-queue empty and completed queues`() {
        val action = selectHomeNextAction(
            focusGames = emptyList(),
            collections = listOf(
                card(1L, "Basic", CollectionMode.BASIC, gameId = 11L),
                card(2L, "Empty queue", CollectionMode.ORDERED_QUEUE),
                card(3L, "Completed queue", CollectionMode.ORDERED_QUEUE, done = true),
                card(4L, "Playable queue", CollectionMode.ORDERED_QUEUE, gameId = 44L),
            ),
        )

        assertEquals(
            HomeNextAction.ContinueCollection(
                collectionId = 4L,
                collectionName = "Playable queue",
                game = HomeNextGame(44L, "Game 44", "icon-44"),
            ),
            action,
        )
    }

    @Test
    fun `current game is suppressed across Focus and collection candidates`() {
        val focus = game(7L, "Running Focus", playtime = 10, completion = 100, lastPlayed = 100L)
        val action = selectHomeNextAction(
            focusGames = listOf(focus),
            collections = listOf(card(9L, "Queue", CollectionMode.ORDERED_QUEUE, gameId = 7L)),
            currentlyPlayingAppId = 7L,
        )

        assertEquals(HomeNextAction.ChooseGame, action)
    }

    @Test
    fun `empty inputs use the choose-game fallback`() {
        assertEquals(
            HomeNextAction.ChooseGame,
            selectHomeNextAction(emptyList(), emptyList()),
        )
    }

    @Test
    fun `family shared focus beyond threshold falls back to collection`() {
        // Steam reports no playtime for family-shared games, so the raw playtimeForever stays 0
        // while the effective playtime (tracked sessions + manual estimate) is past completion.
        val shared = LibraryGame(
            appId = 5L,
            name = "Shared",
            iconUrl = "icon-5",
            playtimeForever = 0,
            completionistMinutes = 100,
            isGoal = true,
            lastPlayedAt = 200L,
            source = GameSource.FAMILY_SHARED,
            manualSharedMinutes = 90,
        )
        val queue = card(9L, "Queue", CollectionMode.ORDERED_QUEUE, gameId = 44L)

        assertEquals(
            HomeNextAction.ContinueCollection(
                collectionId = 9L,
                collectionName = "Queue",
                game = HomeNextGame(44L, "Game 44", "icon-44"),
            ),
            selectHomeNextAction(
                focusGames = listOf(shared),
                collections = listOf(queue),
                trackedMinutesByGame = mapOf(5L to 30),
            ),
        )
    }

    @Test
    fun `family shared focus below threshold remains eligible`() {
        val shared = LibraryGame(
            appId = 5L,
            name = "Shared",
            iconUrl = "icon-5",
            playtimeForever = 0,
            completionistMinutes = 100,
            isGoal = true,
            lastPlayedAt = 200L,
            source = GameSource.FAMILY_SHARED,
            manualSharedMinutes = 20,
        )

        assertEquals(
            HomeNextAction.ContinueFocus(HomeNextGame(5L, "Shared", "icon-5")),
            selectHomeNextAction(
                focusGames = listOf(shared),
                collections = emptyList(),
                trackedMinutesByGame = mapOf(5L to 30),
            ),
        )
    }

    private fun game(
        appId: Long,
        name: String,
        playtime: Int,
        completion: Int?,
        lastPlayed: Long? = null,
    ) = LibraryGame(
        appId = appId,
        name = name,
        iconUrl = "icon-$appId",
        playtimeForever = playtime,
        completionistMinutes = completion,
        isGoal = true,
        lastPlayedAt = lastPlayed,
    )

    private fun card(
        collectionId: Long,
        name: String,
        mode: CollectionMode,
        gameId: Long? = null,
        done: Boolean = false,
    ): HomeCollectionCard {
        val members = gameId?.let {
            listOf(
                CollectionMemberSignals(
                    appId = it,
                    name = "Game $it",
                    playtimeMinutes = 0,
                    completionistMinutes = 100,
                    achievementsUnlocked = null,
                    achievementsTotal = null,
                    manualDone = done,
                ),
            )
        }.orEmpty()
        val banner = CollectionSummary.derive(
            mode = mode,
            sort = mode.defaultSortForTest(),
            targetDate = null,
            members = members,
            today = java.time.LocalDate.of(2026, 9, 22),
            timeBasis = CollectionTimeBasis.COMPLETIONIST,
        )
        return HomeCollectionCard(
            collectionId = collectionId,
            name = name,
            mode = mode,
            accent = null,
            banner = banner,
            games = gameId?.let { listOf(HomeCollectionGame(it, "Game $it", "icon-$it")) }
                .orEmpty(),
        )
    }
}

private fun CollectionMode.defaultSortForTest() = when (this) {
    CollectionMode.BASIC -> com.example.backlogium.domain.CollectionSort.NAME
    CollectionMode.COMPLETION_GOAL -> com.example.backlogium.domain.CollectionSort.COMPLETION_FRACTION
    CollectionMode.DEADLINE_GOAL -> com.example.backlogium.domain.CollectionSort.COMPLETION_FRACTION
    CollectionMode.ORDERED_QUEUE -> com.example.backlogium.domain.CollectionSort.MANUAL_SEQUENCE
}
