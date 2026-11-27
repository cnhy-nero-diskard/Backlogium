package com.example.backlogium

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.notifications.ProgressAlert
import com.example.backlogium.data.notifications.ProgressAlertPlatform
import com.example.backlogium.data.notifications.ProgressCategory
import org.junit.Test
import org.junit.Assert.*

class ProgressAlertTapDeviceTest {
    @Test fun coldAndWarmNotificationIntentsRetainAccountAndGameForTheValidatedRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val platform = ProgressAlertPlatform(context)
        platform.registerChannels()
        val cold = GameNotificationIntent.create(context, GameNotificationTarget("stale-device-fixture", 166L), "progress", "cold")
        ActivityScenario.launch<MainActivity>(cold).use { scenario ->
            lateinit var launched: MainActivity
            scenario.onActivity {
                launched = it
                assertEquals(GameNotificationTarget("stale-device-fixture", 166L), GameNotificationIntent.read(it.intent))
            }
            val alert = ProgressAlert("device-tap-warm", "stale-device-fixture", 0L,
                ProgressCategory.OWNED.name, "167", 167L, "Device tap fixture", "Warm notification route")
            val notification = platform.prepare(alert)
            // Invoke the exact PendingIntent Android invokes when the notification is tapped.
            notification.contentIntent.send()
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(5_000) {
                        var received = false
                        while (!received) {
                            instrumentation.runOnMainSync {
                                received = GameNotificationIntent.read(launched.intent) == GameNotificationTarget("stale-device-fixture", 167L)
                            }
                            if (!received) kotlinx.coroutines.delay(20)
                        }
                    }
                }
            } finally {
                // ActivityScenario filters lifecycle events by its original launch intent.
                instrumentation.runOnMainSync { launched.intent = cold }
            }
        }
    }
}
