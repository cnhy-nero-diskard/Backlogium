package com.example.backlogium

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.example.backlogium.data.updates.OPEN_UPDATE_EXTRA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GameNotificationIntentTest {
    @Test fun readsPreviouslyPostedAchievementPayload() {
        val intent = Intent().setData(Uri.parse("backlogium://achievement/old-event"))
            .putExtra("achievement_account", "fixture")
            .putExtra("achievement_app_id", 42L)

        assertEquals(GameNotificationTarget("fixture", 42L), GameNotificationIntent.read(intent))
    }

    @Test fun legacySharedAndUpdateLaunchesHaveNoGameDestination() {
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
        assertNull(GameNotificationIntent.read(intent))

        intent.putExtra(OPEN_UPDATE_EXTRA, true)
        assertNull(GameNotificationIntent.read(intent))
        assertTrue(intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false))
    }

    @Test fun rejectsMissingAndMalformedTargets() {
        val context = RuntimeEnvironment.getApplication()
        val generic = GameNotificationIntent.create(context, GameNotificationTarget("fixture", 42L), "achievement")
        assertNull(GameNotificationIntent.read(Intent(generic).replaceExtras(Bundle())))
        assertNull(GameNotificationIntent.read(Intent(generic).putExtra("achievement_app_id", "42")))
        assertNull(GameNotificationIntent.read(Intent(generic).putExtra("achievement_account", " ")))
        assertNull(GameNotificationIntent.read(Intent(generic).putExtra("achievement_app_id", -1L)))
    }
}
