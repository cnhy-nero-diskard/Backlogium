package com.example.backlogium.data.updates

private val legacyMarkdownLink = Regex("\\[([^]]+)]\\([^)]*\\)")
private val legacyUrl = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val legacyHtmlTag = Regex("<[^>]+>")
private val legacyBullet = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)")
private val legacyConventionalPrefix = Regex(
    "^(?:feat|feature|fix|perf|performance|docs|test|chore|ci|build|refactor|style)" +
        "(?:\\([^)]*\\))?!?:\\s*",
    RegexOption.IGNORE_CASE,
)
private val legacyContributorSuffix = Regex(
    "\\s*(?:\\(#\\d+\\)|by\\s+@[a-z0-9_.-]+|@[a-z0-9_.-]+)\\s*$",
    RegexOption.IGNORE_CASE,
)

/** Bounded, non-executing fallback for releases published before structured notes existed. */
fun sanitizeLegacyReleaseBody(body: String): String = body
    .lineSequence()
    .mapNotNull { raw ->
        var line = raw.trim()
        if (line.isBlank() || line.startsWith("<details", ignoreCase = true) ||
            line.startsWith("</details", ignoreCase = true) ||
            line.startsWith("<summary", ignoreCase = true) ||
            line.startsWith("#")
        ) {
            return@mapNotNull null
        }
        line = legacyMarkdownLink.replace(line, "$1")
        line = legacyUrl.replace(line, " ")
        line = legacyHtmlTag.replace(line, " ")
        line = line.replace(Regex("^#{1,6}\\s*"), "")
        line = legacyBullet.replace(line, "")
        line = line.replace(legacyConventionalPrefix, "")
        line = line.replace(Regex("[*_`~]"), "")
        line = line.replace(legacyContributorSuffix, "")
        line = line.replace(Regex("\\s+"), " ").trim(' ', '-', ':')
        if (line.isBlank() || line.equals("full changelog", ignoreCase = true)) {
            return@mapNotNull null
        }
        line.take(ReleaseNotesContract.MAX_LEGACY_ITEM_LENGTH)
    }
    .distinct()
    .take(ReleaseNotesContract.MAX_LEGACY_ITEMS)
    .joinToString("\n")

fun AvailableUpdate.releaseNoteSections(): List<ReleaseNoteSection> {
    val structured = structuredNotes?.sections?.filter { it.items.isNotEmpty() }.orEmpty()
    if (structured.isNotEmpty()) return structured
    val legacyItems = sanitizeLegacyReleaseBody(releaseNotes)
        .lineSequence()
        .filter { it.isNotBlank() }
        .toList()
    return if (legacyItems.isEmpty()) {
        emptyList()
    } else {
        listOf(ReleaseNoteSection("legacy", "Release notes", legacyItems))
    }
}

fun AvailableUpdate.releaseSummary(): String =
    structuredNotes?.summary()
        ?: releaseNoteSections().asSequence().flatMap { it.items.asSequence() }.firstOrNull()
        ?: "Maintenance update with no user-visible changes."

fun AvailableUpdate.validatedFullChangelogUrl(): String? =
    structuredNotes?.fullChangelogUrl?.takeIf { isValidFullChangelogUrl(it, tag) }
