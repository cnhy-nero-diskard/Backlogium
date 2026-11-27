package com.example.backlogium.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ConnectivityStatus { UNKNOWN, ONLINE, OFFLINE }

/** A single shell subscription follows Android's validated default network, never a Steam probe. */
@Singleton
class ConnectivityObserver @Inject constructor(@ApplicationContext private val context: Context) {
    val status = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(ConnectivityStatus.UNKNOWN)
            close()
            return@callbackFlow
        }
        val handler = Handler(Looper.getMainLooper())
        fun snapshot() {
            val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            trySend(connectivityStatus(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == manager.activeNetwork) {
                    trySend(connectivityStatus(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
                }
            }
            override fun onLost(network: Network) {
                if (network == manager.activeNetwork) trySend(ConnectivityStatus.OFFLINE) else snapshot()
            }
        }
        manager.registerDefaultNetworkCallback(callback, handler)
        val initial = Runnable { snapshot() }
        handler.post(initial)
        awaitClose {
            handler.removeCallbacks(initial)
            manager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
}

internal fun connectivityStatus(validated: Boolean): ConnectivityStatus =
    if (validated) ConnectivityStatus.ONLINE else ConnectivityStatus.OFFLINE
