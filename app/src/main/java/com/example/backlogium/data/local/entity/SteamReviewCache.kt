package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * The last definitive Steam review summary for one tracked game (add-gap-plan-suggestions).
 *
 * Separate from [GameGenreCache] rather than another column on it because the two come from
 * different endpoints that succeed, fail, and go stale independently: one `appdetails` failure
 * must not hold back durable review progress, and neither must the reverse.
 *
 * [available] is what distinguishes the two definitive answers when [declinedAt] is null. `true`
 * means Steam supplied a usable summary and the three counts are present; `false` means Steam
 * answered and this game has no user reviews at all, which is a fact worth caching so it is not
 * re-requested every run. No row means the game has never been checked — never "zero reviews".
 *
 * A response the Store *declines* to answer writes a row with [declinedAt] so the attempt can leave
 * the immediate queue while the game stays unknown to consumers rather than being recorded as
 * reviewless. The marker is cleared by either definitive outcome.
 *
 * The foreign key cascades like [GameGenreCache]'s, so an account change that clears `games`
 * clears this refreshable cache with it and no separate reset step can be forgotten.
 */
@Entity(
    tableName = "steam_review_cache",
    primaryKeys = ["appId"],
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SteamReviewCache(
    val appId: Long,
    /** Steam's own `review_score_desc` — "Very Positive", … — or null when unavailable. */
    val description: String? = null,
    val positive: Int? = null,
    val negative: Int? = null,
    val total: Int? = null,
    val available: Boolean,
    val checkedAt: Long,
    /** When non-null, the Store declined this attempt and the row is in its retry cooldown. */
    val declinedAt: Long? = null,
)
