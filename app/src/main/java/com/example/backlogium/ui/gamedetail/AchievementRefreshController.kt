package com.example.backlogium.ui.gamedetail

import com.example.backlogium.domain.AchievementRefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AchievementRefreshActionState(
    val pending: Boolean = false,
    val outcome: AchievementRefreshOutcome? = null,
)

/** Presentation tokens prevent a retained ViewModel from publishing another visit's result. */
internal class AchievementRefreshController(
    private val scope: CoroutineScope,
    private val refresh: suspend (Long) -> AchievementRefreshOutcome,
) {
    private val mutableState = MutableStateFlow(AchievementRefreshActionState())
    val state = mutableState.asStateFlow()
    private var appId: Long? = null
    private var visit: String? = null
    private var generation = 0L
    private var job: Job? = null

    fun show(appId: Long, visit: String) {
        if (this.appId == appId && this.visit == visit) return
        leave()
        this.appId = appId
        this.visit = visit
    }

    fun leave() {
        generation++
        job?.cancel()
        job = null
        appId = null
        visit = null
        mutableState.value = AchievementRefreshActionState()
    }

    fun refresh() {
        val id = appId ?: return
        if (mutableState.value.pending) return
        val token = generation
        mutableState.value = AchievementRefreshActionState(pending = true)
        job = scope.launch {
            val outcome = try { refresh(id) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { AchievementRefreshOutcome.FAILED }
            if (token == generation) mutableState.value = AchievementRefreshActionState(outcome = outcome)
        }
    }
}
