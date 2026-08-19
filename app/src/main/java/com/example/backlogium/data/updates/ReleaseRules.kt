package com.example.backlogium.data.updates

/** Converts a GitHub response into an offerable release without doing network or Android work. */
fun GitHubReleaseDto.toAvailableUpdate(installedVersionCode: Long): AvailableUpdate? {
    if (draft || prerelease) return null
    val version = ReleaseVersion.parse(tagName) ?: return null
    if (version.versionCode <= installedVersionCode) return null

    val apk = assets.firstOrNull { asset ->
        asset.name.endsWith(".apk", ignoreCase = true) && asset.browserDownloadUrl.isNotBlank()
    } ?: return null
    val checksum = assets.firstOrNull { asset ->
        asset.name == "${apk.name}.sha256" && asset.browserDownloadUrl.isNotBlank()
    } ?: return null

    return AvailableUpdate(
        tag = version.tag,
        versionName = version.versionName,
        versionCode = version.versionCode,
        releaseName = name.orEmpty().ifBlank { version.versionName },
        releaseNotes = body.orEmpty(),
        apkName = apk.name,
        apkUrl = apk.browserDownloadUrl,
        checksumUrl = checksum.browserDownloadUrl,
    )
}

fun shouldNotifyForUpdate(updateTag: String, declinedTag: String?): Boolean =
    updateTag != declinedTag

object UpdateCheckPolicy {
    const val AUTOMATIC_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    const val AUTOMATIC_GUARD_MILLIS = 20L * 60L * 60L * 1_000L

    /** Manual checks always run; the periodic worker uses the 20-hour guard. */
    fun shouldRun(lastCheckAtMillis: Long?, nowMillis: Long, force: Boolean): Boolean {
        if (force || lastCheckAtMillis == null) return true
        return nowMillis - lastCheckAtMillis >= AUTOMATIC_GUARD_MILLIS
    }
}
