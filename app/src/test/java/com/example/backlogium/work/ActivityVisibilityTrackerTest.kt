package com.example.backlogium.work

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityVisibilityTrackerTest {

    @Test
    fun trackerFollowsActualResumedActivityVisibility() {
        val tracker = ActivityVisibilityTracker(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(Activity::class.java)
            .create()
            .start()
            .resume()

        assertTrue(tracker.hasResumedActivity)

        activity.pause()

        assertFalse(tracker.hasResumedActivity)
        activity.destroy()
    }
}
