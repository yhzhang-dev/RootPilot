package com.hangge.rootpilot.data.repository

import com.hangge.rootpilot.data.local.ActionRecordDao
import com.hangge.rootpilot.data.local.ApiRequestDao
import com.hangge.rootpilot.data.local.DeviceSnapshotDao
import com.hangge.rootpilot.data.local.toDomain
import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HistoryRepository(
    private val snapshotDao: DeviceSnapshotDao,
    private val actionDao: ActionRecordDao,
    private val apiRequestDao: ApiRequestDao,
) {

    val snapshots: Flow<List<DeviceSnapshot>> =
        snapshotDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    val actions: Flow<List<ActionRecord>> =
        actionDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    val apiRequests: Flow<List<ApiRequestLog>> =
        apiRequestDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun allSnapshots(): List<DeviceSnapshot> = snapshotDao.all().map { it.toDomain() }

    suspend fun allActions(): List<ActionRecord> = actionDao.all().map { it.toDomain() }

    suspend fun allApiRequests(): List<ApiRequestLog> =
        apiRequestDao.recent(5_000).map { it.toDomain() }

    suspend fun counts(): HistoryCounts = HistoryCounts(
        snapshots = snapshotDao.count(),
        actions = actionDao.count(),
        apiRequests = apiRequestDao.count(),
    )

    suspend fun clear(type: HistoryType) {
        when (type) {
            HistoryType.SNAPSHOT -> snapshotDao.clear()
            HistoryType.ACTION -> actionDao.clear()
            HistoryType.API -> apiRequestDao.clear()
            HistoryType.ALL -> {
                snapshotDao.clear()
                actionDao.clear()
                apiRequestDao.clear()
            }
        }
    }

    suspend fun recentActions(limit: Int = 50): List<ActionRecord> =
        actionDao.recent(limit).map { it.toDomain() }
}

data class HistoryCounts(
    val snapshots: Int = 0,
    val actions: Int = 0,
    val apiRequests: Int = 0,
)

enum class HistoryType { SNAPSHOT, ACTION, API, ALL }
