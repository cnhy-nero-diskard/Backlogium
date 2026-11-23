package com.example.backlogium.ui.navigation

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.backlogium.GameNotificationTarget
import com.example.backlogium.data.repo.GameNotificationTargets
import com.example.backlogium.data.setup.SetupAccounts
import com.example.backlogium.work.setup.FirstRunSetupGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

@HiltViewModel
internal class GameNotificationViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val accounts: SetupAccounts,
    private val setup: FirstRunSetupGateway,
    private val targets: GameNotificationTargets,
) : ViewModel() {
    private val pending = savedState.getStateFlow<Bundle?>("pendingGameNotification", null)
    val requests = pending.mapNotNull { it?.toTarget() }

    fun request(target: GameNotificationTarget) {
        savedState["pendingGameNotification"] = bundleOf("account" to target.account, "appId" to target.appId)
    }

    fun consume(target: GameNotificationTarget): Boolean {
        if (pending.value?.toTarget() != target) return false
        savedState["pendingGameNotification"] = null
        return true
    }

    suspend fun canOpen(target: GameNotificationTarget, routeReady: Flow<Boolean>): Boolean {
        val ready = combine(accounts.owners, setup.firstRunSetupActive, routeReady) { owner, setupActive, routeIsReady ->
            when {
                owner?.steamId != target.account -> false
                setupActive || !routeIsReady -> null
                else -> true
            }
        }.filterNotNull().first()
        return ready && targets.canOpen(target.account, target.appId)
    }

    private fun Bundle.toTarget() = getString("account")?.let { GameNotificationTarget(it, getLong("appId")) }
}
