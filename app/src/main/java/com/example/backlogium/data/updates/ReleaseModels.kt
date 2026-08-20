package com.example.backlogium.data.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The subset of a GitHub release response needed by the updater. */
@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GitHubReleaseAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
)

/** A release that has passed channel, asset, and installed-version checks. */
data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val releaseName: String,
    val releaseNotes: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    /** Versioned release-note asset URL; it is never needed for an offline install retry. */
    val structuredNotesUrl: String? = null,
    /** Optional, validated presentation metadata persisted alongside the offer. */
    val structuredNotes: ReleaseNotesPresentation? = null,
) {
    /** A stable, private filename; an asset name is never used as a filesystem path. */
    val artifactFileName: String
        get() = "backlogium-update-${tag.removePrefix("v")}.apk"
}

data class AppUpdateState(
    val available: AvailableUpdate? = null,
    val lastCheckAtMillis: Long? = null,
    val lastSeenTag: String? = null,
    val declinedTag: String? = null,
    val installStatus: UpdateInstallStatus = UpdateInstallStatus.Idle,
)

/** Persisted PackageInstaller lifecycle state shared with the UI process. */
sealed interface UpdateInstallStatus {
    data object Idle : UpdateInstallStatus
    data class Started(val tag: String) : UpdateInstallStatus
    data class AwaitingUserAction(val tag: String) : UpdateInstallStatus
    data class Failed(val tag: String, val message: String) : UpdateInstallStatus
}

sealed interface UpdateCheckResult {
    val update: AvailableUpdate?

    data class Available(
        override val update: AvailableUpdate,
        val notificationPosted: Boolean,
    ) : UpdateCheckResult

    data class NoUpdate(
        val reason: NoUpdateReason,
        override val update: AvailableUpdate? = null,
    ) : UpdateCheckResult

    data class Failed(
        val cause: Throwable? = null,
        override val update: AvailableUpdate? = null,
    ) : UpdateCheckResult

    data class SkippedRecent(
        override val update: AvailableUpdate?,
    ) : UpdateCheckResult
}

enum class NoUpdateReason {
    CURRENT_VERSION,
    INVALID_RELEASE,
    DRAFT_OR_PRERELEASE,
    MISSING_ASSET,
}

sealed interface UpdateProgress {
    data class Downloading(val bytesRead: Long, val totalBytes: Long?) : UpdateProgress
    data object VerifyingDigest : UpdateProgress
    data object VerifyingSigner : UpdateProgress
    data object Installing : UpdateProgress
}

sealed interface UpdateInstallResult {
    data object Started : UpdateInstallResult
    data object PermissionRequired : UpdateInstallResult
    data class Failed(val message: String) : UpdateInstallResult
}
