package com.example.backlogium.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Storage-agnostic seam for progress-event delivery marks. */
interface ProgressMarksStore {
    val marks: Flow<ProgressMarks>
    suspend fun read(): ProgressMarks
    suspend fun write(marks: ProgressMarks)

    /**
     * Atomically replace the current marks with `transform(current)`, applied against whatever is
     * latest at the moment the update actually lands, and return the value written. Every caller
     * MUST derive its result solely from the function's argument — never from a `read()` taken
     * earlier — otherwise a concurrent [update] can be silently overwritten by one computed from a
     * stale snapshot.
     */
    suspend fun update(transform: (ProgressMarks) -> ProgressMarks): ProgressMarks
}

/** Lightweight JVM-friendly default used by direct [GamificationUpdater] construction in tests. */
class InMemoryProgressMarksStore(
    initial: ProgressMarks = ProgressMarks(),
) : ProgressMarksStore {
    private val state = MutableStateFlow(initial)

    override val marks: Flow<ProgressMarks> = state.asStateFlow()

    override suspend fun read(): ProgressMarks = state.value

    override suspend fun write(marks: ProgressMarks) {
        state.value = marks
    }

    override suspend fun update(transform: (ProgressMarks) -> ProgressMarks): ProgressMarks {
        lateinit var result: ProgressMarks
        state.update { current -> transform(current).also { result = it } }
        return result
    }
}
