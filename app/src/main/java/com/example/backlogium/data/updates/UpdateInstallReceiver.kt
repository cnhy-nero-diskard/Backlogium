package com.example.backlogium.data.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.example.backlogium.work.ActivityVisibilityTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Bridges PackageInstaller's asynchronous status back to persisted UI state and cleanup. */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject
    lateinit var updateStateStore: UpdateStateStore

    @Inject
    lateinit var notifier: UpdateNotifier

    @Inject
    lateinit var installRecovery: UpdateInstallRecovery

    @Inject
    lateinit var activityVisibility: ActivityVisibilityTracker

    override fun onReceive(context: Context, intent: Intent) {
        val artifact = intent.getStringExtra(INSTALL_ARTIFACT_PATH_EXTRA)?.let(::File)
        val tag = intent.getStringExtra(INSTALL_UPDATE_TAG_EXTRA).orEmpty()
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.parcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    val message = "Android did not provide an update confirmation screen."
                    cleanup(artifact)
                    showFailure(context, tag, message)
                } else {
                    val launched = if (isAppVisible()) {
                        runCatching {
                            context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                    } else {
                        false
                    }
                    if (!launched) {
                        val notified = runCatching {
                            notifier.notifyInstallConfirmation(confirmation)
                        }.getOrDefault(false)
                        if (notified) {
                            persistStatus { updateStateStore.markInstallPending(tag) }
                        } else {
                            cleanup(artifact)
                            showFailureToast(context, UPDATE_CONFIRMATION_UNAVAILABLE_MESSAGE)
                            persistStatus {
                                installRecovery.recoverFromUnavailableNotification(tag, sessionId)
                            }
                        }
                    } else {
                        persistStatus { updateStateStore.markInstallPending(tag) }
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                cleanup(artifact)
                clearAvailableAndRelaunch(context, tag)
            }

            else -> {
                cleanup(artifact)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "The update was not installed."
                showFailure(context, tag, message)
            }
        }
    }

    private fun showFailure(context: Context, tag: String, message: String) {
        showFailureToast(context, message)
        persistStatus {
            if (tag.isBlank()) {
                updateStateStore.clearInstallStatus()
            } else {
                updateStateStore.markInstallFailed(tag, message)
            }
        }
    }

    private fun showFailureToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun persistStatus(action: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { action() }
            pendingResult.finish()
        }
    }

    private fun isAppVisible(): Boolean =
        activityVisibility.hasResumedActivity

    private fun cleanup(artifact: File?) {
        artifact?.delete()
        artifact?.let { File(it.absolutePath + UPDATE_ARTIFACT_PARTIAL_SUFFIX).delete() }
    }

    private fun clearAvailableAndRelaunch(context: Context, tag: String) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                updateStateStore.clearInstallStatus()
                updateStateStore.clearAvailable()
            }
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (launchIntent != null) {
                // A plain PendingIntent.send() here is a background activity launch: the
                // system UI that drove the install (not this app) owns the foreground at
                // this point, and Android blocks BAL from a receiver with no visible window.
                // Only start the activity directly while this app is actually visible;
                // otherwise fall back to a tap-to-open notification, which a user tap exempts
                // from the restriction.
                val launched = if (isAppVisible()) {
                    runCatching { context.startActivity(launchIntent) }.isSuccess
                } else {
                    false
                }
                if (!launched) {
                    runCatching { notifier.notifyInstallComplete(tag.removePrefix("v")) }
                }
            }
            pendingResult.finish()
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
