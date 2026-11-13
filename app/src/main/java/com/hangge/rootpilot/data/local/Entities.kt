package com.hangge.rootpilot.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.ActionType
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.domain.model.DeviceSnapshot

@Entity(tableName = "device_snapshots")
data class DeviceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAt: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val batteryTemperatureC: Float?,
    val memoryFreeBytes: Long,
    val storageFreeBytes: Long,
    val rootAvailable: Boolean,
    val appCount: Int,
)

@Entity(tableName = "action_records")
data class ActionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val packageName: String?,
    val action: String,
    val success: Boolean,
    val message: String,
)

@Entity(tableName = "api_requests")
data class ApiRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val method: String,
    val path: String,
    val statusCode: Int,
    val durationMs: Long,
)

fun DeviceSnapshotEntity.toDomain(): DeviceSnapshot = DeviceSnapshot(
    id = id,
    capturedAt = capturedAt,
    batteryPercent = batteryPercent,
    charging = charging,
    batteryTemperatureC = batteryTemperatureC,
    memoryFreeBytes = memoryFreeBytes,
    storageFreeBytes = storageFreeBytes,
    rootAvailable = rootAvailable,
    appCount = appCount,
)

fun DeviceSnapshot.toEntity(): DeviceSnapshotEntity = DeviceSnapshotEntity(
    id = id,
    capturedAt = capturedAt,
    batteryPercent = batteryPercent,
    charging = charging,
    batteryTemperatureC = batteryTemperatureC,
    memoryFreeBytes = memoryFreeBytes,
    storageFreeBytes = storageFreeBytes,
    rootAvailable = rootAvailable,
    appCount = appCount,
)

fun ActionRecordEntity.toDomain(): ActionRecord = ActionRecord(
    id = id,
    createdAt = createdAt,
    packageName = packageName,
    action = runCatching { ActionType.valueOf(action) }.getOrDefault(ActionType.SNAPSHOT),
    success = success,
    message = message,
)

fun ApiRequestEntity.toDomain(): ApiRequestLog = ApiRequestLog(
    id = id,
    createdAt = createdAt,
    method = method,
    path = path,
    statusCode = statusCode,
    durationMs = durationMs,
)
