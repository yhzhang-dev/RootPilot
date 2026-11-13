package com.hangge.rootpilot.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.NetworkInterface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

data class AgentSensorSample(
    val type: Int,
    val name: String,
    val values: List<Float>,
    val accuracy: Int,
    val capturedAtNanos: Long,
)

data class AgentSensorInfo(
    val name: String,
    val type: Int,
    val vendor: String,
    val version: Int,
    val powerMilliamp: Float,
    val resolution: Float,
    val wakeUp: Boolean,
)

data class AgentNetworkInfo(
    val transports: List<String>,
    val metered: Boolean,
    val validated: Boolean,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val addresses: List<String>,
)

/** Read-only platform facts exposed to the desktop Agent. */
class AgentDeviceInfoSource(private val context: Context) {

    fun sensors(): List<AgentSensorInfo> {
        val manager = context.getSystemService(SensorManager::class.java)
        return manager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            AgentSensorInfo(
                name = sensor.name,
                type = sensor.type,
                vendor = sensor.vendor,
                version = sensor.version,
                powerMilliamp = sensor.power,
                resolution = sensor.resolution,
                wakeUp = sensor.isWakeUpSensor,
            )
        }.sortedWith(compareBy({ it.type }, { it.name }))
    }

    suspend fun sampleSensor(type: Int, timeoutMs: Long = 3_000): AgentSensorSample =
        withTimeout(timeoutMs.coerceIn(500, 10_000)) {
            suspendCancellableCoroutine { continuation ->
                val manager = context.getSystemService(SensorManager::class.java)
                val sensor = manager.getDefaultSensor(type)
                if (sensor == null) {
                    continuation.resumeWith(Result.failure(IllegalArgumentException("设备没有 type=$type 的传感器")))
                    return@suspendCancellableCoroutine
                }
                val thread = HandlerThread("RootPilotSensorSample").apply { start() }
                val handler = Handler(thread.looper)
                lateinit var listener: SensorEventListener
                fun cleanup() {
                    manager.unregisterListener(listener)
                    thread.quitSafely()
                }
                listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (!continuation.isActive) return
                        val sample = AgentSensorSample(
                            type = event.sensor.type,
                            name = event.sensor.name,
                            values = event.values.toList(),
                            accuracy = event.accuracy,
                            capturedAtNanos = event.timestamp,
                        )
                        cleanup()
                        continuation.resume(sample)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                continuation.invokeOnCancellation { cleanup() }
                if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)) {
                    cleanup()
                    continuation.resumeWith(Result.failure(IllegalStateException("无法注册传感器监听")))
                }
            }
        }

    fun network(): AgentNetworkInfo {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("BLUETOOTH")
        }
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                        .filterNot { it.isLoopbackAddress }
                        .map { "${networkInterface.name}:${it.hostAddress}" }
                }
        }.getOrDefault(emptyList())
        return AgentNetworkInfo(
            transports = transports,
            metered = manager.isActiveNetworkMetered,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            downstreamKbps = capabilities?.linkDownstreamBandwidthKbps ?: 0,
            upstreamKbps = capabilities?.linkUpstreamBandwidthKbps ?: 0,
            addresses = addresses,
        )
    }

    fun permissionStates(): Map<String, Boolean> = linkedMapOf(
        "locationFine" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
        "locationBackground" to granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        "camera" to granted(Manifest.permission.CAMERA),
        "microphone" to granted(Manifest.permission.RECORD_AUDIO),
        "notifications" to (
            Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
            ),
        "notificationListener" to enabledNotificationListeners().contains(context.packageName),
        "accessibility" to enabledAccessibilityServices().contains(context.packageName),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun enabledAccessibilityServices(): String =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

    private fun enabledNotificationListeners(): String =
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
}
