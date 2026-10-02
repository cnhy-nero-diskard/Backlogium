package com.example.backlogium.ui.library

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

/** One activity's discovery context. Never serialized into saved state or preferences. */
@ActivityRetainedScoped
class LibraryVisitState @Inject constructor() {
    internal val filters = MutableStateFlow(LibraryFilters())
    val generation = MutableStateFlow(0L)
    var scrollAnchor: LibraryScrollAnchor? = null
        private set
    var absenceStartedAt: Long? = null
        private set
    private var inLibraryFlow = false
    private var foreground = true

    fun routeChanged(inLibraryFlow: Boolean, now: Long = SystemClock.elapsedRealtime()) {
        if (this.inLibraryFlow && !inLibraryFlow) depart(now)
        this.inLibraryFlow = inLibraryFlow
        if (inLibraryFlow && foreground) resume(now)
    }

    fun background(now: Long = SystemClock.elapsedRealtime()) {
        foreground = false
        if (inLibraryFlow) depart(now)
    }

    fun foreground(now: Long = SystemClock.elapsedRealtime()) {
        foreground = true
        if (inLibraryFlow) resume(now)
    }

    fun captureScroll(anchor: LibraryScrollAnchor, forGeneration: Long) {
        if (forGeneration == generation.value) scrollAnchor = anchor
    }

    private fun depart(now: Long) {
        if (absenceStartedAt == null) absenceStartedAt = now
    }

    private fun resume(now: Long) {
        val start = absenceStartedAt ?: return
        if (now - start >= ABSENCE_TIMEOUT_MS) {
            filters.value = LibraryFilters()
            scrollAnchor = null
            generation.value += 1
        }
        absenceStartedAt = null
    }

    companion object {
        const val ABSENCE_TIMEOUT_MS = 300_000L
    }
}

data class LibraryScrollAnchor(
    val gameId: Long? = null,
    val itemKey: String? = null,
    val index: Int = 0,
    val offset: Int = 0,
)

/** The shell's activity-scoped ViewModel shares the retained holder with destination ViewModels. */
@HiltViewModel
class LibraryVisitViewModel @Inject constructor(val visit: LibraryVisitState) : ViewModel()
