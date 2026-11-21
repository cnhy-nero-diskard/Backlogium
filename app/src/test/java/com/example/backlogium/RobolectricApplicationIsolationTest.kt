package com.example.backlogium

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** No application override here: these tests guard the suite's shared configuration. */
@RunWith(RobolectricTestRunner::class)
class RobolectricApplicationIsolationTest {
    @Test
    fun defaultApplicationDoesNotStartProductionBackgroundWork() {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w412dp-h915dp-mdpi")
    fun screenConfigurationKeepsApplicationIsolation() {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
    }
}
