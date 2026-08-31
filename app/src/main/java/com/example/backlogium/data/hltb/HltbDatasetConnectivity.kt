package com.example.backlogium.data.hltb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow validated-network seam used only by explicit dataset checks. */
fun interface HltbDatasetConnectivity {
    fun isOnline(): Boolean
}

@Singleton
class AndroidHltbDatasetConnectivity @Inject constructor(
    @ApplicationContext private val context: Context,
) : HltbDatasetConnectivity {
    override fun isOnline(): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (_: RuntimeException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
