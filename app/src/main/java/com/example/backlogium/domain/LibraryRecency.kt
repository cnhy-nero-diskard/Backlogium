package com.example.backlogium.domain

/**
 * One game's recency state: the single signal, if any, that something about it changed lately.
 *
 * The three are mutually exclusive by construction rather than by convention — see
 * [LibraryRecency.derive] — so a game can never carry two at once and no surface needs a rule for
 * which to draw.
 */
enum class GameRecencyState {
    /** Arrived while the app was watching, and not yet played. */
    NEWLY_ADDED,

    /** Its first ever recorded session happened recently. Fires once per game, for life. */
    NEWLY_PLAYED,

    /** Played again after a dormant period. */
    RETURNED,
}

/**
 * The recency signals, in one place: both windows, the write-time dormancy evaluation, and the
 * read-time state derivation.
 *
 * Pure — no Android, no Room, no clock. Every instant is supplied by the caller, which is what
 * makes the event-time discipline below enforceable rather than aspirational.
 */
object LibraryRecency {

    /**
     * How long a recency state lasts. Every state expires by arithmetic against this window, so
     * the Library returns to quiet on its own with no write and no scheduled work.
     *
     * A constant, not a setting: a preference here would need a backup field and an explanation,
     * for a threshold nobody has an opinion about until they have lived with the default.
     */
    const val BADGE_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1_000

    /**
     * How long a game must go unplayed for the next play to count as a return.
     *
     * Also a constant, and applied at *write* time (see [evaluateReturn]) — so if this value is
     * ever tuned, returns already recorded keep the meaning they had when they were observed.
     * That is the honest behaviour for a fact about the past, and it is the unavoidable
     * consequence of recording the fact rather than deriving it.
     */
    const val DORMANCY_THRESHOLD_MILLIS: Long = 30L * 24 * 60 * 60 * 1_000

    /**
     * Whether a game's play increase ended a dormant period, and when — the value to stamp on
     * `returnedToPlayAt`, or null to record nothing and leave any existing value untouched.
     *
     * **Every quantity here is an event time — when the play happened — never an observation time
     * — when the app found out.** The two diverge by however long the app went unsynced, which is
     * unbounded (a phone left off, a revoked key, airplane mode on holiday), and conflating them
     * produces two distinct defects: returns that never happened (a 29-day gap observed three days
     * late reads as 32), and a badge window that starts when the sync ran rather than when the
     * player returned.
     *
     * @param previousLastPlayedAt the game's stored last-played time **as it stood before this
     *   poll's update**. Reading it after the update would establish nothing: the write that
     *   signals the return is the same write that destroys the evidence there was a gap.
     * @param mostRecentSessionEndAt the end of the game's most recent recorded session. Taking the
     *   later of this and [previousLastPlayedAt] unifies what would otherwise be a primary path
     *   and a fallback — a game with recorded sessions and a game whose only prior play predates
     *   the install go through the same expression, and whichever source knows more wins.
     * @param observedPlayAt when the play happened, as the caller best knows it — and the *only*
     *   source of that instant, deliberately: a path that read a clock of its own could not be
     *   given a correct event time by any caller, however much better its information. A periodic
     *   poll passes Steam's newly reported last-played time; a post-play fetch passes the session
     *   end that triggered it, which is seconds old and therefore *better* than a coarse Steam
     *   value. A caller with neither passes null, and no return is recorded: the failure mode stays
     *   a missing badge rather than a wrong one.
     * @param now the present, used only to clamp [observedPlayAt] — a source's clock may lead the
     *   device's, and a return must never be recorded in the future.
     */
    fun evaluateReturn(
        previousLastPlayedAt: Long?,
        mostRecentSessionEndAt: Long?,
        observedPlayAt: Long?,
        now: Long,
    ): Long? {
        val playAt = minOf(observedPlayAt ?: return null, now)
        val previousPlayAt = maxOfNotNull(previousLastPlayedAt, mostRecentSessionEndAt) ?: return null
        return playAt.takeIf { it - previousPlayAt >= DORMANCY_THRESHOLD_MILLIS }
    }

    /**
     * The one state a game currently carries, or null.
     *
     * Deliberately **not** a function of `lastPlayedAt`: whether a play ended a dormant period was
     * decided at observation time by [evaluateReturn], and no amount of stored state can recover a
     * gap after the write that closed it. All this does is ask whether a recorded observation
     * still falls inside the badge window.
     *
     * Precedence exists because the conditions genuinely overlap and each overlap has an obviously
     * better answer. A game bought and played the same day is *newly played* — that it is also new
     * is the less interesting half. A long-dormant game finally started for the first time is
     * *newly played*, not *returned*; you never left it. [GameRecencyState.NEWLY_ADDED] ranks last
     * because it carries the least information: only that nothing has happened yet.
     *
     * @param playtimeForever gates [GameRecencyState.NEWLY_ADDED] on being zero, which is what
     *   keeps the states from fighting rather than merely ranking them: a game bought and played
     *   *leaves* newly-added rather than being outranked by it, so the two "new" states describe
     *   successive phases instead of competing views.
     * @param firstSessionAt the game's earliest recorded session, which is what makes newly-played
     *   fire once per game for life. The alternative reading — "played within the last N days" —
     *   would badge most of an active player's library at once, making the badge describe the
     *   population rather than the exception.
     */
    fun derive(
        firstSeenAt: Long?,
        returnedToPlayAt: Long?,
        playtimeForever: Int,
        firstSessionAt: Long?,
        now: Long,
    ): GameRecencyState? = when {
        isRecent(firstSessionAt, now) -> GameRecencyState.NEWLY_PLAYED
        isRecent(returnedToPlayAt, now) -> GameRecencyState.RETURNED
        playtimeForever == 0 && isRecent(firstSeenAt, now) -> GameRecencyState.NEWLY_ADDED
        else -> null
    }

    /**
     * Whether a recorded instant is still inside the badge window.
     *
     * An instant slightly ahead of [now] — clock skew, a source's clock leading the device's —
     * counts as recent rather than as absent: it is the most recent thing that could possibly have
     * happened.
     */
    private fun isRecent(at: Long?, now: Long): Boolean =
        at != null && now - at < BADGE_WINDOW_MILLIS

    private fun maxOfNotNull(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }
}
