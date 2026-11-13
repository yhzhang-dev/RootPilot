package com.hangge.rootpilot.data.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AgentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val provider: String,
    val capturedAt: Long,
)

class AgentLocationSource(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun current(): AgentLocation = withContext(Dispatchers.IO) {
        require(hasLocationPermission()) { "未授予定位权限" }
        val manager = context.getSystemService(LocationManager::class.java)
        val enabledProviders = manager.getProviders(true)
        val location = enabledProviders
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?: error("暂无定位数据，请先打开系统定位并让任一应用完成定位")
        AgentLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            provider = location.provider.orEmpty(),
            capturedAt = location.time,
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
