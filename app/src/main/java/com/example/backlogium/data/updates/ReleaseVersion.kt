package com.example.backlogium.data.updates

/**
 * The release version encoding used by the release workflow and Android's upgrade comparison:
 * major occupies the millions, minor the thousands, and patch the units. Each component is kept
 * below 1000 so ordering remains lexicographic across major/minor/patch boundaries.
 */
data class ParsedReleaseVersion(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
)

object ReleaseVersion {
    private val tagPattern = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")

    /** Parses only the full release channel form accepted by release.yml. */
    fun parse(tag: String): ParsedReleaseVersion? {
        val match = tagPattern.matchEntire(tag) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        if (major >= COMPONENT_RADIX || minor >= COMPONENT_RADIX || patch >= COMPONENT_RADIX) {
            return null
        }
        return ParsedReleaseVersion(
            tag = tag,
            versionName = "$major.$minor.$patch",
            versionCode = major * MAJOR_MULTIPLIER + minor * MINOR_MULTIPLIER + patch,
        )
    }

    private const val COMPONENT_RADIX = 1_000L
    private const val MAJOR_MULTIPLIER = 1_000_000L
    private const val MINOR_MULTIPLIER = 1_000L
}
