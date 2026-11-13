package com.hangge.rootpilot.service

import com.hangge.rootpilot.data.system.AgentDeviceInfoSource
import com.hangge.rootpilot.data.system.AgentLocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class AgentTelemetryManager(
    private val locationSource: AgentLocationSource,
    private val deviceInfo: AgentDeviceInfoSource,
    private val events: AgentEventHub,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationJob: Job? = null
    private val sensorJobs = ConcurrentHashMap<Int, Job>()

    fun startLocation(intervalMs: Long): Long {
        val interval = intervalMs.coerceIn(2_000, 300_000)
        locationJob?.cancel()
        locationJob = scope.launch {
            while (isActive) {
                runCatching { locationSource.current() }
                    .onSuccess { location ->
                        events.publish(
                            "location.sample",
                            JSONObject()
                                .put("latitude", location.latitude)
                                .put("longitude", location.longitude)
                                .put("accuracyMeters", location.accuracyMeters)
                                .put("provider", location.provider)
                                .put("capturedAt", location.capturedAt),
                        )
                    }
                    .onFailure { events.publish("location.error", JSONObject().put("message", it.message)) }
                delay(interval)
            }
        }
        return interval
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    fun startSensor(type: Int, intervalMs: Long): Long {
        val interval = intervalMs.coerceIn(200, 60_000)
        sensorJobs.remove(type)?.cancel()
        sensorJobs[type] = scope.launch {
            while (isActive) {
                runCatching { deviceInfo.sampleSensor(type, timeoutMs = 5_000) }
                    .onSuccess { sample ->
                        events.publish(
                            "sensor.sample",
                            JSONObject()
                                .put("type", sample.type)
                                .put("name", sample.name)
                                .put("values", JSONArray(sample.values))
                                .put("accuracy", sample.accuracy)
                                .put("capturedAtNanos", sample.capturedAtNanos),
                        )
                    }
                    .onFailure { events.publish("sensor.error", JSONObject().put("type", type).put("message", it.message)) }
                delay(interval)
            }
        }
        return interval
    }

    fun stopSensor(type: Int) {
        sensorJobs.remove(type)?.cancel()
    }

    fun status(): JSONObject = JSONObject()
        .put("locationRunning", locationJob?.isActive == true)
        .put("sensorTypes", JSONArray(sensorJobs.filterValues { it.isActive }.keys.toList()))
}
