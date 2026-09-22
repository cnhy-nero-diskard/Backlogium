package com.example.backlogium.ui.home

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.CollectionMemberSignals
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNextActionTest {

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
