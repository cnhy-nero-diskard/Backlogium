package com.example.backlogium.data.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.example.backlogium.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Bridges PackageInstaller's asynchronous status back to cleanup and the app relaunch. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val artifact = intent.getStringExtra(INSTALL_ARTIFACT_PATH_EXTRA)?.let(::File)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.parcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                artifact?.delete()
                File(artifact?.absolutePath.orEmpty() + UpdateArtifactStore.PARTIAL_SUFFIX).delete()
                clearAvailableAndRelaunch(context)
            }

            else -> {
                artifact?.delete()
                File(artifact?.absolutePath.orEmpty() + UpdateArtifactStore.PARTIAL_SUFFIX).delete()
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "The update was not installed."
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearAvailableAndRelaunch(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { UpdateDataStore(context.applicationContext).clearAvailable() }
            pendingResult.finish()
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: return@launch
            PendingIntent.getActivity(
                context,
                RELAUNCH_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send()
        }
    }

    private companion object {
        const val RELAUNCH_REQUEST_CODE = 4203
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
