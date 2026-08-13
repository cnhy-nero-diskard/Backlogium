package com.example.backlogium.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Storage-agnostic seam for progress-event delivery marks. */
interface ProgressMarksStore {
    val marks: Flow<ProgressMarks>
    suspend fun read(): ProgressMarks
    suspend fun write(marks: ProgressMarks)
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
}
