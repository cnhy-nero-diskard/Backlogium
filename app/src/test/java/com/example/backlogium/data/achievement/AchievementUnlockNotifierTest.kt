package com.example.backlogium.data.achievement

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import com.example.backlogium.domain.AchievementUnlockGroup
import com.example.backlogium.domain.AchievementUnlockItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AchievementUnlockNotifierTest {
    private val group = AchievementUnlockGroup("event", "fixture", 1, "Fixture", 1,
        listOf(AchievementUnlockItem("first", "First", null, 1, 5.0)))

    @Test fun retryReplacesStableIdentityWithOnlyAlertOnceAndExactGameDestination() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidAchievementUnlockNotifier(context)
        assertEquals(AchievementUnlockNotifier.Result.POSTED, notifier.post(group))
        assertEquals(AchievementUnlockNotifier.Result.POSTED, notifier.post(group))
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single()
        assertEquals("achievement:event", notification.tag)
        assertTrue(notification.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val intent = shadowOf(notification.notification.contentIntent).savedIntent
        assertEquals(1L, intent.getLongExtra(OPEN_ACHIEVEMENT_GAME, 0))
        assertEquals("fixture", intent.getStringExtra(OPEN_ACHIEVEMENT_ACCOUNT))
        notifier.post(group.copy(eventId = "another"))
        assertEquals(2, manager.activeNotifications.size)
    }

    @Test fun deniedPermissionSuppressesInsteadOfDeferringAnAlertBacklog() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(AchievementUnlockNotifier.Result.SUPPRESSED, AndroidAchievementUnlockNotifier(context).post(group))
    }
}
