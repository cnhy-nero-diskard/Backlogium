package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Outcome of matching a Steam game name to HowLongToBeat entries. */
enum class HltbMatchStatus {
    /** A single confident match was resolved automatically (or confirmed via review). */
    RESOLVED,

    /** Multiple/low-confidence candidates; awaits user selection in the review surface. */
    NEEDS_REVIEW,

    /** The search returned no entries; the game carries no completion lengths. */
    UNMATCHED,
}

/**
 * Cached HowLongToBeat completion lengths for a game, keyed by Steam [appId]. Kept in its
 * own table (not on [Game]) so HLTB writes stay entirely off the Steam-sync path — see the
 * add-hltb-integration design. All four metrics are retained so a consumer can switch which
 * one it uses (goal progress → Main Story; gamification → Completionist) without a re-fetch.
 *
 * [candidatesJson] holds the serialized candidate list only while [matchStatus] is
 * [HltbMatchStatus.NEEDS_REVIEW]; it is cleared once the match resolves.
 */
@Entity(
    tableName = "hltb_data",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HltbData(
    @PrimaryKey val appId: Long,
    val hltbId: Long? = null,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val completionistMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val fetchedAt: Long,
    val matchStatus: HltbMatchStatus,
    val candidatesJson: String? = null,
)
