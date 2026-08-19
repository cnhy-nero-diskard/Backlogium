package com.example.backlogium.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRulesTest {
    @Test
    fun newerReleaseWithMatchingChecksumIsOfferable() {
        val update = release("v1.8.0").toAvailableUpdate(installedVersionCode = 1_007_000L)

        assertEquals("v1.8.0", update?.tag)
        assertEquals("1.8.0", update?.versionName)
        assertEquals("app-release.apk.sha256", update?.checksumUrl?.substringAfterLast('/'))
    }

    @Test
    fun equalAndOlderReleasesAreNotOfferable() {
        assertNull(release("v1.7.0").toAvailableUpdate(1_007_000L))
        assertNull(release("v1.6.9").toAvailableUpdate(1_007_000L))
    }

    @Test
    fun draftsPrereleasesInvalidTagsAndMissingAssetsAreNotOfferable() {
        assertNull(release("v1.8.0", draft = true).toAvailableUpdate(1L))
        assertNull(release("v1.8.0", prerelease = true).toAvailableUpdate(1L))
        assertNull(release("release-1.8.0").toAvailableUpdate(1L))
        assertNull(release("v1.8.0", includeApk = false).toAvailableUpdate(1L))
        assertNull(release("v1.8.0", includeChecksum = false).toAvailableUpdate(1L))
    }

    @Test
    fun declineSuppressesOnlyTheSameTag() {
        assertFalse(shouldNotifyForUpdate("v1.8.0", "v1.8.0"))
        assertTrue(shouldNotifyForUpdate("v1.9.0", "v1.8.0"))
        assertTrue(shouldNotifyForUpdate("v1.8.0", null))
    }

    @Test
    fun automaticChecksUseTwentyHourGuardButManualChecksAlwaysRun() {
        val now = 100L * 60L * 60L * 1_000L
        val justChecked = now - 19L * 60L * 60L * 1_000L
        val due = now - 21L * 60L * 60L * 1_000L

        assertFalse(UpdateCheckPolicy.shouldRun(justChecked, now, force = false))
        assertTrue(UpdateCheckPolicy.shouldRun(due, now, force = false))
        assertTrue(UpdateCheckPolicy.shouldRun(justChecked, now, force = true))
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        includeApk: Boolean = true,
        includeChecksum: Boolean = true,
    ) = GitHubReleaseDto(
        tagName = tag,
        name = "Backlogium $tag",
        body = "Notes for $tag",
        draft = draft,
        prerelease = prerelease,
        assets = buildList {
            if (includeApk) {
                add(
                    GitHubReleaseAssetDto(
                        name = "app-release.apk",
                        browserDownloadUrl = "https://example.test/app-release.apk",
                        size = 10L,
                    ),
                )
            }
            if (includeChecksum) {
                add(
                    GitHubReleaseAssetDto(
                        name = "app-release.apk.sha256",
                        browserDownloadUrl = "https://example.test/app-release.apk.sha256",
                        size = 64L,
                    ),
                )
            }
        },
    )
}
