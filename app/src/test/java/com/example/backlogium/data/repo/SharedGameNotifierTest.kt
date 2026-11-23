package com.example.backlogium.data.repo

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import com.example.backlogium.GameNotificationIntent
import com.example.backlogium.GameNotificationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedGameNotifierTest {
    @Test fun noticesRetainTheirOwnImmutableAccountAndGameDestinations() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidSharedGameNotifier(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        assertTrue(notifier.notifyAdmitted("first", 1L, "Alpha"))
        val first = manager.activeNotifications.single().notification.contentIntent
        assertTrue(notifier.notifyAdmitted("first", 2L, "Beta"))
        assertTrue(notifier.notifyAdmitted("second", 1L, "Alpha"))
        assertTrue(notifier.notifyAdmitted("first", 1L, "Alpha"))

        val pendingIntents = manager.activeNotifications.map { it.notification.contentIntent }
        val targets = pendingIntents.associateBy { GameNotificationIntent.read(shadowOf(it).savedIntent) }
        assertEquals(3, pendingIntents.size)
        assertEquals(3, pendingIntents.toSet().size)
        assertTrue(pendingIntents.all { it.isImmutable })
        assertEquals(
            setOf(GameNotificationTarget("first", 1L), GameNotificationTarget("first", 2L), GameNotificationTarget("second", 1L)),
            targets.keys,
        )
        assertEquals(first, targets[GameNotificationTarget("first", 1L)])
    }
}
