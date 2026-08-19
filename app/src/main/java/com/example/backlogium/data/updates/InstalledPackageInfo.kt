package com.example.backlogium.data.updates

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledPackageInfo(
    val versionName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
)

interface InstalledPackageInfoProvider {
    fun installed(): InstalledPackageInfo

    fun archiveSignerDigests(apk: File): Set<String>?
}

/** Reads the values Android itself uses for upgrade and signing decisions. */
@Singleton
class AndroidInstalledPackageInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledPackageInfoProvider {
    private val packageManager: PackageManager
        get() = context.packageManager

    override fun installed(): InstalledPackageInfo {
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        return InstalledPackageInfo(
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.longVersionCode,
            signerDigests = packageInfo.signingInfo.signerDigestsOrNull()
                ?: error("Installed package has no signing certificate"),
        )
    }

    override fun archiveSignerDigests(apk: File): Set<String>? =
        packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )?.signingInfo?.signerDigestsOrNull()
}

private fun android.content.pm.SigningInfo?.signerDigestsOrNull(): Set<String>? {
    val signatures = this?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: return null
    return signatures.map { signature ->
        Base64.encodeToString(signature.toByteArray(), Base64.NO_WRAP)
    }.toSet()
}
