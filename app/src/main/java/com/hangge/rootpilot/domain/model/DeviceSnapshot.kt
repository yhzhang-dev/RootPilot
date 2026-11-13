package com.hangge.rootpilot.domain.model

/** A point-in-time device reading, persisted in Room and exposed by the read-only provider. */
data class DeviceSnapshot(
    val id: Long = 0,
    val capturedAt: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val batteryTemperatureC: Float?,
    val memoryFreeBytes: Long,
    val storageFreeBytes: Long,
    val rootAvailable: Boolean,
    val appCount: Int,
)

/** Identity values that do not belong in the snapshot table (they never change at runtime). */
data class DeviceProfile(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appCount: Int,
    val memoryTotalBytes: Long,
    val storageTotalBytes: Long,
)

data class BatteryState(
    val percent: Int? = null,
    val charging: Boolean? = null,
    val temperatureC: Float? = null,
    val updatedAt: Long = 0L,
)
