package com.example.backlogium.data.backup

import com.example.backlogium.data.local.dao.GamePreferenceDao
import com.example.backlogium.data.local.entity.GamePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeGamePreferenceDao : GamePreferenceDao {
    private val rows = mutableMapOf<Long, GamePreference>()
    override fun observeAll(): Flow<List<GamePreference>> = flowOf(rows.values.toList())
    override suspend fun getAll() = rows.values.toList()
    override fun observe(appId: Long): Flow<GamePreference?> = flowOf(rows[appId])
    override suspend fun upsert(preference: GamePreference) { rows[preference.appId] = preference }
    override suspend fun deleteAll() { rows.clear() }
}
