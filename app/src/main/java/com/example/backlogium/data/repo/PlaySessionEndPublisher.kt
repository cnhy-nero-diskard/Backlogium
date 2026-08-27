package com.example.backlogium.data.repo

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One observed play session ending: the game that stopped, and when the observation said so.
 *
 * [endedAt] is carried rather than left to the consumer's clock because the work that acts on this
 * runs minutes later — see add-post-play-sync, where every attempt of a schedule must report the
 * same play instant regardless of which attempt happened to observe the playtime increase.
 */
data class PlaySessionEnd(val appId: Long, val endedAt: Long)

/**
 * Makes the end of an observed session available to work that acts on it, without giving presence
 * observation a dependency on any of that work.
 *
 * [publish] never suspends, never touches the network or the database, and cannot fail — it is
 * called from inside [LiveStatusRepository]'s poll, which `live-status` requires to stay
 * independent of library-scale work. A consumer that is slow, absent, or broken therefore cannot
 * affect presence: the buffer drops the oldest event rather than applying backpressure, and a
 * session end nobody is listening for is simply lost, which is the pre-existing behaviour.
 */
@Singleton
class PlaySessionEndPublisher @Inject constructor() {

    private val _events = MutableSharedFlow<PlaySessionEnd>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Session ends, hot: only work already collecting when one is published observes it. */
    val events: SharedFlow<PlaySessionEnd> = _events.asSharedFlow()

    fun publish(sessionEnd: PlaySessionEnd) {
        _events.tryEmit(sessionEnd)
    }

    private companion object {
        /** A player cannot end sessions faster than a consumer drains them; this is slack, not a queue. */
        const val BUFFER_CAPACITY = 8
    }
}
