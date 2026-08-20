package com.example.backlogium.ui.util

import android.view.HapticFeedbackConstants
import com.example.backlogium.domain.ProgressEvent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticsTest {

    @Test
    fun progressEvents_mapExhaustively_andStreakBreakIsSilent() {
        val events = listOf(
            ProgressEvent.LevelUp(from = 1, to = 2),
            ProgressEvent.QuestMet(LocalDate.of(2026, 8, 19)),
            ProgressEvent.StreakMilestone(days = 7),
            ProgressEvent.StreakBroken(previousLength = 7),
        )

        assertEquals(
            listOf(
                HapticIntent.LevelUp,
                HapticIntent.QuestMet,
                HapticIntent.StreakMilestone,
                HapticIntent.Silent,
            ),
            events.map(ProgressEvent::toHapticIntent),
        )
    }

    @Test
    fun committedOutcomes_areDispatchedOnce_andOpeningOrCancellingIsSilent() {
        val player = RecordingHapticPlayer()

        // These are the only committed outcomes that call sites are allowed to dispatch.
        player.playIfNotSilent(HapticIntent.Confirm)
        player.playIfNotSilent(HapticIntent.Confirm)
        player.playIfNotSilent(HapticIntent.Toggle(enabled = true))
        player.playIfNotSilent(HapticIntent.Toggle(enabled = false))
        player.playIfNotSilent(HapticIntent.Reject)

        // Opening and cancelling a confirmation deliberately make no dispatch call.
        assertEquals(
            listOf(
                HapticIntent.Confirm,
                HapticIntent.Confirm,
                HapticIntent.Toggle(enabled = true),
                HapticIntent.Toggle(enabled = false),
                HapticIntent.Reject,
            ),
            player.intents,
        )
    }

    @Test
    fun silentIntent_neverReachesPlayer() {
        val player = RecordingHapticPlayer()

        player.playIfNotSilent(HapticIntent.Silent)

        assertEquals(emptyList<HapticIntent>(), player.intents)
        assertNull(hapticConstantFor(HapticIntent.Silent, sdkInt = 36))
    }

    @Test
    fun toggle_fallsBackBelowApi34_andUsesOnOffConstantsOnApi34() {
        assertEquals(
            HapticFeedbackConstants.CONTEXT_CLICK,
            hapticConstantFor(HapticIntent.Toggle(enabled = true), sdkInt = 33),
        )
        assertEquals(
            HapticFeedbackConstants.CONTEXT_CLICK,
            hapticConstantFor(HapticIntent.Toggle(enabled = false), sdkInt = 33),
        )
        assertEquals(
            HapticFeedbackConstants.TOGGLE_ON,
            hapticConstantFor(HapticIntent.Toggle(enabled = true), sdkInt = 34),
        )
        assertEquals(
            HapticFeedbackConstants.TOGGLE_OFF,
            hapticConstantFor(HapticIntent.Toggle(enabled = false), sdkInt = 34),
        )
        assertNotEquals(
            hapticConstantFor(HapticIntent.Toggle(enabled = true), sdkInt = 34),
            hapticConstantFor(HapticIntent.Toggle(enabled = false), sdkInt = 34),
        )
    }

    private class RecordingHapticPlayer : HapticPlayer {
        val intents = mutableListOf<HapticIntent>()

        override fun play(intent: HapticIntent) {
            intents += intent
        }
    }
}
