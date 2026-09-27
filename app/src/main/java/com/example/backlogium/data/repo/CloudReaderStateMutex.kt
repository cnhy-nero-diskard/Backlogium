package com.example.backlogium.data.repo

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes reader promotion with Steam commits that consume generation-bound evidence. */
@Singleton
class CloudReaderStateMutex @Inject constructor() {
    internal val mutex = Mutex()
}
