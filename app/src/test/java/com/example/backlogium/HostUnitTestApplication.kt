package com.example.backlogium

import android.app.Application
import androidx.work.Configuration

/**
 * Neutral host application: unit fixtures construct their own recovery graph and own its lifetime.
 * WorkManager may initialize on demand, but production Hilt startup, credential seeding and network
 * scheduling never run merely because Robolectric creates an application for an unrelated test.
 */
class HostUnitTestApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
