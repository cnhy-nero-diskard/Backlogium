package com.example.backlogium.data.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

const val INSTALL_ARTIFACT_PATH_EXTRA = "com.example.backlogium.UPDATE_ARTIFACT_PATH"

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(update: AvailableUpdate, artifact: File): UpdateInstallResult {
        if (!canRequestPackageInstalls()) {
            runCatching { openInstallPermissionSettings() }
            return UpdateInstallResult.PermissionRequired
        }

        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setAppPackageName(context.packageName)
                    setSize(artifact.length())
                }
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                artifact.inputStream().use { input ->
                    session.openWrite("base.apk", 0L, artifact.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                    putExtra(INSTALL_ARTIFACT_PATH_EXTRA, artifact.absolutePath)
                }
                val statusPendingIntent = PendingIntent.getBroadcast(
                    context,
                    update.tag.hashCode(),
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                // Keep the default user confirmation behavior; setRequireUserAction(false) is
                // intentionally not used so the first and every later update behave identically.
                session.commit(statusPendingIntent.intentSender)
            }
            UpdateInstallResult.Started
        } catch (failure: Exception) {
            artifact.delete()
            File(artifact.absolutePath + UpdateArtifactStore.PARTIAL_SUFFIX).delete()
            UpdateInstallResult.Failed(failure.message ?: "The update could not be installed.")
        }
    }
}
