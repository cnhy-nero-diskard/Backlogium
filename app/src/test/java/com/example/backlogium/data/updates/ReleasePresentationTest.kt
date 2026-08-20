package com.example.backlogium.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePresentationTest {
    @Test
    fun validStructuredNotesRequireSupportedSchemaMatchingTagAndBoundedContent() {
        val notes = parseStructuredReleaseNotes(validJson(), "v1.8.0")

        assertNotNull(notes)
        assertEquals("A new home view.", notes?.sections?.first()?.items?.single())
        assertEquals(
            "https://github.com/cnhy-nero-diskard/Backlogium/compare/v1.7.0...v1.8.0",
            notes?.fullChangelogUrl,
        )
        assertNull(parseStructuredReleaseNotes(validJson().replace("v1.8.0", "v1.9.0"), "v1.8.0"))
        assertNull(parseStructuredReleaseNotes(validJson().replace("\"schema_version\": 1", "\"schema_version\": 2"), "v1.8.0"))
    }

    @Test
    fun malformedUrlsAndOversizedItemsAreIgnoredWithoutGatingTheRelease() {
        val badUrl = validJson().replace(
            "https://github.com/cnhy-nero-diskard/Backlogium/compare/v1.7.0...v1.8.0",
            "https://example.test/changelog",
        )
        val oversized = validJson().replace("A new home view.", "x".repeat(181))

        assertNull(parseStructuredReleaseNotes(badUrl, "v1.8.0"))
        assertNull(parseStructuredReleaseNotes(oversized, "v1.8.0"))
    }

    @Test
    fun legacySanitizerRemovesMarkdownConventionalPrefixesContributorsAndUrls() {
        val sanitized = sanitizeLegacyReleaseBody(
            """
            ## What's Changed
            * fix: Keep offline progress by @contributor
            **Full Changelog**: https://github.com/cnhy-nero-diskard/Backlogium/compare/v1.7.0...v1.8.0
            """.trimIndent(),
        )

        assertEquals("Keep offline progress", sanitized)
        assertFalse(sanitized.contains("*"))
        assertFalse(sanitized.contains("https://"))
        assertFalse(sanitized.contains("@contributor"))
        assertFalse(sanitized.contains("fix:"))
    }

    @Test
    fun notesAssetIsSelectedIndependentlyAndCannotChangeArtifactSelection() {
        val release = GitHubReleaseDto(
            tagName = "v1.8.0",
            name = "Backlogium 1.8.0",
            body = "## What's Changed\n* fix: readable fallback by @user",
            assets = listOf(
                GitHubReleaseAssetDto("custom.apk", "https://example.test/custom.apk", 10L),
                GitHubReleaseAssetDto("custom.apk.sha256", "https://example.test/custom.sha256", 64L),
                GitHubReleaseAssetDto(
                    "Backlogium-1.8.0-release-notes.json",
                    "https://example.test/notes.json",
                    200L,
                ),
            ),
        )

        val update = release.toAvailableUpdate(installedVersionCode = 1L)

        assertEquals("custom.apk", update?.apkName)
        assertEquals("https://example.test/custom.sha256", update?.checksumUrl)
        assertEquals("https://example.test/notes.json", update?.structuredNotesUrl)
        assertEquals("readable fallback", update?.releaseNotes)
        assertTrue(update?.versionName == "1.8.0")
    }

    private fun validJson() = """
        {
          "schema_version": 1,
          "tag": "v1.8.0",
          "sections": [
            {"key":"features","title":"Features","items":["A new home view."]},
            {"key":"fixes","title":"Fixes","items":[]},
            {"key":"performance","title":"Performance","items":[]},
            {"key":"maintenance","title":"Maintenance","items":[]}
          ],
          "technical_details": [
            {"number":42,"title":"feat: add home view","url":"https://github.com/cnhy-nero-diskard/Backlogium/pull/42","category":"features"}
          ],
          "full_changelog_url":"https://github.com/cnhy-nero-diskard/Backlogium/compare/v1.7.0...v1.8.0"
        }
    """.trimIndent()
}
