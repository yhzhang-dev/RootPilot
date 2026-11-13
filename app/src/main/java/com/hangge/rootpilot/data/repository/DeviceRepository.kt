package com.hangge.rootpilot.data.repository

import android.content.Context
import android.content.Intent
import com.hangge.rootpilot.data.local.ActionRecordDao
import com.hangge.rootpilot.data.local.ActionRecordEntity
import com.hangge.rootpilot.data.local.DeviceSnapshotDao
import com.hangge.rootpilot.data.local.toDomain
import com.hangge.rootpilot.data.local.toEntity
import com.hangge.rootpilot.data.system.BatteryMonitor
import com.hangge.rootpilot.data.system.DeviceInfoSource
import com.hangge.rootpilot.domain.model.ActionType
import com.hangge.rootpilot.domain.model.BatteryState
import com.hangge.rootpilot.domain.model.DeviceProfile
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class DeviceRepository(
    private val context: Context,
    private val snapshotDao: DeviceSnapshotDao,
    private val actionDao: ActionRecordDao,
    private val deviceInfo: DeviceInfoSource,
    batteryMonitor: BatteryMonitor,
) {

    val snapshots: Flow<List<DeviceSnapshot>> =
        snapshotDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    val latestSnapshot: Flow<DeviceSnapshot?> =
        snapshotDao.observeLatest().map { it?.toDomain() }

    val battery: StateFlow<BatteryState> = batteryMonitor.state

    suspend fun profile(): DeviceProfile = deviceInfo.profile()

    suspend fun latestOnce(): DeviceSnapshot? =
        snapshotDao.recent(1).firstOrNull()?.toDomain()

    suspend fun captureSnapshot(rootAvailable: Boolean): DeviceSnapshot {
        val snapshot = deviceInfo.snapshot(rootAvailable)
        val id = snapshotDao.insert(snapshot.toEntity())
        actionDao.insert(
            ActionRecordEntity(
                createdAt = snapshot.capturedAt,
                packageName = null,
                action = ActionType.SNAPSHOT.name,
                success = true,
                message = "快照已保存",
            ),
        )
        context.sendBroadcast(
            Intent(ACTION_SNAPSHOT_SAVED).setPackage(context.packageName),
        )
        return snapshot.copy(id = id)
    }

    companion object {
        const val ACTION_SNAPSHOT_SAVED = "com.hangge.rootpilot.action.SNAPSHOT_SAVED"
    }
}
