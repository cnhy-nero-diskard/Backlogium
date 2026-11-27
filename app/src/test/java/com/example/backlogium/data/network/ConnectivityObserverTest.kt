package com.example.backlogium.data.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadow.api.Shadow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectivityObserverTest {
    @Test fun `cold offline snapshot updates on validation and unregisters on stop`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val network = manager.activeNetwork!!
        val capabilities = ShadowNetworkCapabilities.newInstance().also {
            Shadow.extract<ShadowNetworkCapabilities>(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadow.setNetworkCapabilities(network, capabilities)
        val seen = mutableListOf<ConnectivityStatus>()
        val job = launch { ConnectivityObserver(context).status.collect { seen += it } }
        runCurrent()
        assertTrue(seen.isEmpty()) // unknown until Android has supplied the initial snapshot
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(listOf(ConnectivityStatus.OFFLINE), seen)
        val callback = shadow.networkCallbacks.single()
        Shadow.extract<ShadowNetworkCapabilities>(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(network, capabilities)
        runCurrent()
        assertEquals(ConnectivityStatus.ONLINE, seen.last())
        // A Steam failure has no input into this observer: validated internet remains online.
        callback.onCapabilitiesChanged(network, capabilities)
        runCurrent()
        assertEquals(2, seen.size)
        Shadow.extract<ShadowNetworkCapabilities>(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(network, capabilities)
        runCurrent()
        assertEquals(ConnectivityStatus.OFFLINE, seen.last())
        job.cancel()
        runCurrent()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun `cold validated network never emits offline`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork!!,
            ShadowNetworkCapabilities.newInstance().also {
                Shadow.extract<ShadowNetworkCapabilities>(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            })
        val seen = mutableListOf<ConnectivityStatus>()
        val job = launch { ConnectivityObserver(context).status.collect { seen += it } }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(listOf(ConnectivityStatus.ONLINE), seen)
        job.cancel()
        runCurrent()
    }
}
