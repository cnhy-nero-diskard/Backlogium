package com.example.backlogium.data.repo

import com.example.backlogium.data.setup.SetupOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class HistoryImportReadiness { NEEDS_SYNC, READY, PENDING, IMPORTED }

interface HistoryImportGateway {
    val historyImportReadiness: Flow<HistoryImportReadiness>
    val historyLibrarySyncing: Flow<Boolean> get() = flowOf(false)
    suspend fun importSteamHistory(): Boolean
    suspend fun importSteamHistory(owner: SetupOwner?): Boolean = importSteamHistory()
    suspend fun resumeSteamHistory(owner: SetupOwner? = null) = Unit
    fun syncHistoryLibrary() = Unit
}
