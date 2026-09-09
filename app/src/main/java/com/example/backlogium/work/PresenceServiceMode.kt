package com.example.backlogium.work

import com.example.backlogium.data.repo.NowPlaying

/** What the foreground presence service should do after one Steam observation. */
internal enum class PresenceServiceMode {
    PLAYING,
    MONITORING,
    STOP,
}

/**
 * An active game always keeps the existing session tracker alive. The opt-in monitor only changes
 * the idle case: it keeps polling before the next game begins instead of stopping immediately.
 *
 * [rawNowPlaying] is the raw Steam running-game signal before hidden-game suppression, so a hidden
 * game keeps the service alive for session recording (hidden-games spec, "Sessions still recorded")
 * even when the UI-facing [NowPlaying] resolves to [NowPlaying.NotPlaying].
 */
internal fun presenceServiceMode(
    liveMonitorEnabled: Boolean,
    rawNowPlaying: NowPlaying,
): PresenceServiceMode = when (rawNowPlaying) {
    is NowPlaying.InGame -> PresenceServiceMode.PLAYING
    NowPlaying.NotPlaying -> if (liveMonitorEnabled) {
        PresenceServiceMode.MONITORING
    } else {
        PresenceServiceMode.STOP
    }
}
