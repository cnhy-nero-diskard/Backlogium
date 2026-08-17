package com.example.backlogium.work

import android.app.Activity
import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks actual resumed Activities rather than the delayed process-level lifecycle signal.
 * [PresenceServiceStarter] reads this immediately before a foreground-service launch.
 */
@Singleton
class ActivityVisibilityTracker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val resumedActivities = Collections.newSetFromMap(
        IdentityHashMap<Activity, Boolean>(),
    )

    @Volatile
    var hasResumedActivity: Boolean = false
        private set

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            synchronized(resumedActivities) {
                resumedActivities += activity
                hasResumedActivity = resumedActivities.isNotEmpty()
            }
        }

        override fun onActivityPaused(activity: Activity) {
            synchronized(resumedActivities) {
                resumedActivities -= activity
                hasResumedActivity = resumedActivities.isNotEmpty()
            }
        }

        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            synchronized(resumedActivities) {
                resumedActivities -= activity
                hasResumedActivity = resumedActivities.isNotEmpty()
            }
        }
    }

    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
    }
}
