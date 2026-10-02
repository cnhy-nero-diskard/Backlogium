package com.example.backlogium.domain

import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class DailyActivityKey(val accountId: String, val date: LocalDate)
data class DailyActivityRead(val key: DailyActivityKey, val activity: DailyActivity? = null)

/** Cancels obsolete date/account reads and clears rows before subscribing to the replacement. */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeDailyActivity(
    keys: Flow<DailyActivityKey>,
    read: (DailyActivityKey) -> Flow<DailyActivity>,
): Flow<DailyActivityRead> = keys.distinctUntilChanged().flatMapLatest { key ->
    read(key).map { DailyActivityRead(key, it) }.onStart { emit(DailyActivityRead(key)) }
}
