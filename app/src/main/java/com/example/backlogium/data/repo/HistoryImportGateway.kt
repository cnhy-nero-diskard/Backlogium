package com.example.backlogium.data.repo

import kotlinx.coroutines.flow.Flow

enum class HistoryImportReadiness { NEEDS_SYNC, READY, IMPORTED }

interface HistoryImportGateway {
    val historyImportReadiness: Flow<HistoryImportReadiness>
    suspend fun importSteamHistory(): Boolean
}
