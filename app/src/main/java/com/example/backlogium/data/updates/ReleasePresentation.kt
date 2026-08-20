package com.example.backlogium.data.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ReleaseNotesContract {
    const val SCHEMA_VERSION = 1
    const val MAX_DOWNLOAD_BYTES = 64 * 1024
    const val MAX_SECTION_COUNT = 4
    const val MAX_ITEMS_PER_SECTION = 12
    const val MAX_ITEM_LENGTH = 180
    const val MAX_TECHNICAL_ENTRIES = 100
    const val MAX_TECHNICAL_TITLE_LENGTH = 180
    const val MAX_LEGACY_ITEMS = 12
    const val MAX_LEGACY_ITEM_LENGTH = 180
    const val MAX_NOTIFICATION_LENGTH = 180

    const val REPOSITORY_URL = "https://github.com/cnhy-nero-diskard/Backlogium"

    val SECTION_ORDER: List<Pair<String, String>> = listOf(
        "features" to "Features",
        "fixes" to "Fixes",
        "performance" to "Performance",
        "maintenance" to "Maintenance",
    )
}

/** Wire format published as Backlogium-<version>-release-notes.json. */
@Serializable
data class StructuredReleaseNotesDto(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val tag: String = "",
    val sections: List<StructuredReleaseSectionDto> = emptyList(),
    @SerialName("technical_details") val technicalDetails: List<StructuredTechnicalDetailDto> = emptyList(),
    @SerialName("full_changelog_url") val fullChangelogUrl: String? = null,
)

@Serializable
data class StructuredReleaseSectionDto(
    val key: String = "",
    val title: String = "",
    val items: List<String> = emptyList(),
)

@Serializable
data class StructuredTechnicalDetailDto(
    val number: Int = 0,
    val title: String = "",
    val url: String = "",
    val category: String = "technical",
)

/** Validated app-facing projection. Technical PR details intentionally do not cross this boundary. */
@Serializable
data class ReleaseNotesPresentation(
    @SerialName("schema_version") val schemaVersion: Int,
    val tag: String,
    val sections: List<ReleaseNoteSection>,
    @SerialName("full_changelog_url") val fullChangelogUrl: String? = null,
) {
    fun firstUserFacingItem(): String? = sections
        .filter { it.key in setOf("features", "fixes", "performance") }
        .asSequence()
        .flatMap { it.items.asSequence() }
        .firstOrNull()

    fun summary(): String = (
        firstUserFacingItem()
            ?: sections.firstOrNull { it.key == "maintenance" }?.items?.firstOrNull()
            ?: "Maintenance update with no user-visible changes."
        ).take(ReleaseNotesContract.MAX_NOTIFICATION_LENGTH)
}

@Serializable
data class ReleaseNoteSection(
    val key: String,
    val title: String,
    val items: List<String>,
)

private val structuredNotesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = false
}

fun parseStructuredReleaseNotes(raw: String, expectedTag: String): ReleaseNotesPresentation? =
    runCatching {
        structuredNotesJson.decodeFromString<StructuredReleaseNotesDto>(raw)
            .toValidatedPresentation(expectedTag)
    }.getOrNull()

fun StructuredReleaseNotesDto.toValidatedPresentation(expectedTag: String): ReleaseNotesPresentation? {
    if (schemaVersion != ReleaseNotesContract.SCHEMA_VERSION || tag != expectedTag) return null
    if (sections.isEmpty() || sections.size > ReleaseNotesContract.MAX_SECTION_COUNT) return null

    val expected = ReleaseNotesContract.SECTION_ORDER
    var previousIndex = -1
    val validatedSections = sections.map { section ->
        val index = expected.indexOfFirst { it.first == section.key }
        if (index <= previousIndex || index < 0) return null
        previousIndex = index
        if (section.title != expected[index].second) return null
        if (section.items.size > ReleaseNotesContract.MAX_ITEMS_PER_SECTION) return null
        val items = section.items.map { item ->
            if (!item.isBoundedReleaseText(ReleaseNotesContract.MAX_ITEM_LENGTH)) return null
            item
        }
        ReleaseNoteSection(key = section.key, title = section.title, items = items)
    }

    if (technicalDetails.size > ReleaseNotesContract.MAX_TECHNICAL_ENTRIES) return null
    if (technicalDetails.any { detail ->
            detail.number <= 0 ||
                !detail.title.isBoundedReleaseText(ReleaseNotesContract.MAX_TECHNICAL_TITLE_LENGTH) ||
                !isValidPullRequestUrl(detail.url, detail.number)
        }
    ) return null

    if (fullChangelogUrl != null && !isValidFullChangelogUrl(fullChangelogUrl, expectedTag)) return null
    return ReleaseNotesPresentation(
        schemaVersion = schemaVersion,
        tag = tag,
        sections = validatedSections,
        fullChangelogUrl = fullChangelogUrl,
    )
}

fun ReleaseNotesPresentation.validatedFor(expectedTag: String): ReleaseNotesPresentation? =
    StructuredReleaseNotesDto(
        schemaVersion = schemaVersion,
        tag = tag,
        sections = sections.map { section ->
            StructuredReleaseSectionDto(
                key = section.key,
                title = section.title,
                items = section.items,
            )
        },
        fullChangelogUrl = fullChangelogUrl,
    ).toValidatedPresentation(expectedTag)

fun isValidPullRequestUrl(url: String, number: Int): Boolean =
    url == "${ReleaseNotesContract.REPOSITORY_URL}/pull/$number"

fun isValidFullChangelogUrl(url: String, expectedTag: String): Boolean {
    if (ReleaseVersion.parse(expectedTag) == null) return false
    val pattern = Regex(
        "^${Regex.escape(ReleaseNotesContract.REPOSITORY_URL)}/compare/" +
            "v[0-9]+\\.[0-9]+\\.[0-9]+\\.\\.\\.${Regex.escape(expectedTag)}$",
    )
    return pattern.matches(url)
}

private fun String.isBoundedReleaseText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && this == trim() && none { it.isISOControl() }
