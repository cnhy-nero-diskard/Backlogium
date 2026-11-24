package com.example.backlogium.ui.navigation

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.backlogium.GameNotificationTarget
import com.example.backlogium.data.repo.GameNotificationTargetValidator
import com.example.backlogium.data.setup.SetupAccounts
import com.example.backlogium.work.setup.FirstRunSetupGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@HiltViewModel
internal class GameNotificationViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val accounts: SetupAccounts,
    private val setup: FirstRunSetupGateway,
    private val targets: GameNotificationTargetValidator,
) : ViewModel() {
    private val pending = savedState.getStateFlow<Bundle?>(PENDING_REQUEST, null)

    fun request(target: GameNotificationTarget) {
        savedState[PENDING_REQUEST] = bundleOf("account" to target.account, "appId" to target.appId)
    }

    suspend fun collectOpenRequests(
        routeReady: Flow<Boolean>,
        onOpen: (GameNotificationTarget) -> Unit,
    ) {
        pending.collectLatest { request ->
            val target = request?.toTarget() ?: return@collectLatest
            val canOpen = canOpen(target, routeReady)
            if (pending.value !== request) return@collectLatest
            savedState[PENDING_REQUEST] = null
            if (canOpen) onOpen(target)
        }
    }

    private suspend fun canOpen(target: GameNotificationTarget, routeReady: Flow<Boolean>): Boolean {
        val readiness = combine(accounts.owners, setup.firstRunSetupActive, routeReady) { owner, setupActive, routeIsReady ->
            when {
                owner?.steamId != target.account -> false
                setupActive || !routeIsReady -> null
                else -> true
            }
        }
        while (readiness.filterNotNull().first()) {
            val eligible = targets.canOpen(target.account, target.appId)
            // If setup started during validation, wait for it and check eligibility again.
            readiness.first()?.let { return it && eligible }
        }
        return false
    }

    private fun Bundle.toTarget() = getString("account")?.let { GameNotificationTarget(it, getLong("appId")) }

    private companion object {
        const val PENDING_REQUEST = "pendingGameNotification"
    }
}
