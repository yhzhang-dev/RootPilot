package com.hangge.rootpilot.data.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.StatFs
import com.hangge.rootpilot.domain.model.BatteryState
import com.hangge.rootpilot.domain.model.DeviceProfile
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads device identity, memory, storage, and battery through platform APIs only. */
class DeviceInfoSource(private val context: Context) {

    suspend fun profile(): DeviceProfile = withContext(Dispatchers.IO) {
        val memory = memoryInfo()
        val stat = StatFs(context.filesDir.absolutePath)
        DeviceProfile(
            model = Build.MODEL ?: "Unknown",
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            appCount = installedPackageCount(),
            memoryTotalBytes = memory.totalMem,
            storageTotalBytes = stat.totalBytes,
        )
    }

    suspend fun snapshot(rootAvailable: Boolean): DeviceSnapshot = withContext(Dispatchers.IO) {
        val memory = memoryInfo()
        val stat = StatFs(context.filesDir.absolutePath)
        val battery = batteryState()
        DeviceSnapshot(
            capturedAt = System.currentTimeMillis(),
            batteryPercent = battery.percent,
            charging = battery.charging,
            batteryTemperatureC = battery.temperatureC,
            memoryFreeBytes = memory.availMem,
            storageFreeBytes = stat.availableBytes,
            rootAvailable = rootAvailable,
            appCount = installedPackageCount(),
        )
    }

    fun batteryState(): BatteryState {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return com.hangge.rootpilot.data.system.BatteryParser.from(sticky)
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val manager = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    }

    private fun installedPackageCount(): Int =
        runCatching { context.packageManager.getInstalledPackages(0).size }.getOrDefault(0)
}
