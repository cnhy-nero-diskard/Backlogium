package com.example.backlogium

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.BuildConfig

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // The debug build installs under a `.debug` application-ID suffix, so resolve
        // against the generated APPLICATION_ID instead of a hardcoded package literal.
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
