package com.example.backlogium.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Returns whether Android currently has a validated default network. */
internal fun Context.hasValidatedInternet(): Boolean =
    (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        ?.hasValidatedInternet() == true

/**
 * Reads the platform's validated-network signal without making HLTB observation depend on an
 * unavailable API in older Android shadows or unusual device implementations.
 */
internal fun ConnectivityManager.hasValidatedInternet(): Boolean = try {
    val network = activeNetwork ?: return false
    getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
} catch (_: RuntimeException) {
    false
} catch (_: LinkageError) {
    false
}
