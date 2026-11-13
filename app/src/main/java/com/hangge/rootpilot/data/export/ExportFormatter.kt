package com.hangge.rootpilot.data.export

import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.domain.model.DeviceProfile
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Serializes history for export. The Agent token is deliberately not part of any export shape:
 * there is no field for it, and no code path here can reach TokenStore.
 */
object ExportFormatter {

    private val isoTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun toJson(
        exportedAt: Long,
        device: DeviceProfile,
        snapshots: List<DeviceSnapshot>,
        actions: List<ActionRecord>,
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("exportedAt", exportedAt)
        root.put(
            "device",
            JSONObject()
                .put("model", device.model)
                .put("manufacturer", device.manufacturer)
                .put("androidRelease", device.androidRelease)
                .put("sdkInt", device.sdkInt)
                .put("appCount", device.appCount)
                .put("memoryTotalBytes", device.memoryTotalBytes)
                .put("storageTotalBytes", device.storageTotalBytes),
        )
        root.put(
            "snapshots",
            JSONArray().apply {
                snapshots.forEach { snapshot ->
                    put(
                        JSONObject()
                            .put("id", snapshot.id)
                            .put("capturedAt", snapshot.capturedAt)
                            .put("batteryPercent", snapshot.batteryPercent ?: JSONObject.NULL)
                            .put("charging", snapshot.charging ?: JSONObject.NULL)
                            .put("batteryTemperatureC", snapshot.batteryTemperatureC ?: JSONObject.NULL)
                            .put("memoryFreeBytes", snapshot.memoryFreeBytes)
                            .put("storageFreeBytes", snapshot.storageFreeBytes)
                            .put("rootAvailable", snapshot.rootAvailable)
                            .put("appCount", snapshot.appCount),
                    )
                }
            },
        )
        root.put(
            "actions",
            JSONArray().apply {
                actions.forEach { action ->
                    put(
                        JSONObject()
                            .put("id", action.id)
                            .put("createdAt", action.createdAt)
                            .put("packageName", action.packageName ?: JSONObject.NULL)
                            .put("action", action.action.name)
                            .put("success", action.success)
                            .put("message", action.message),
                    )
                }
            },
        )
        return root.toString(2)
    }

    fun actionsToCsv(actions: List<ActionRecord>): String = buildCsv(
        header = listOf("createdAt", "time", "packageName", "action", "success", "message"),
        rows = actions.map {
            listOf(
                it.createdAt.toString(),
                isoTimestamp.format(Instant.ofEpochMilli(it.createdAt)),
                it.packageName.orEmpty(),
                it.action.name,
                it.success.toString(),
                it.message,
            )
        },
    )

    fun snapshotsToCsv(snapshots: List<DeviceSnapshot>): String = buildCsv(
        header = listOf(
            "capturedAt", "time", "batteryPercent", "charging", "batteryTemperatureC",
            "memoryFreeBytes", "storageFreeBytes", "rootAvailable", "appCount",
        ),
        rows = snapshots.map {
            listOf(
                it.capturedAt.toString(),
                isoTimestamp.format(Instant.ofEpochMilli(it.capturedAt)),
                it.batteryPercent?.toString().orEmpty(),
                it.charging?.toString().orEmpty(),
                it.batteryTemperatureC?.toString().orEmpty(),
                it.memoryFreeBytes.toString(),
                it.storageFreeBytes.toString(),
                it.rootAvailable.toString(),
                it.appCount.toString(),
            )
        },
    )

    fun apiToCsv(requests: List<ApiRequestLog>): String = buildCsv(
        header = listOf("createdAt", "time", "method", "path", "statusCode", "durationMs"),
        rows = requests.map {
            listOf(
                it.createdAt.toString(),
                isoTimestamp.format(Instant.ofEpochMilli(it.createdAt)),
                it.method,
                it.path,
                it.statusCode.toString(),
                it.durationMs.toString(),
            )
        },
    )

    fun timestampLabel(epochMillis: Long): String =
        isoTimestamp.format(Instant.ofEpochMilli(epochMillis))

    private fun buildCsv(header: List<String>, rows: List<List<String>>): String = buildString {
        append(header.joinToString(",") { escape(it) })
        append("\r\n")
        rows.forEach { row ->
            append(row.joinToString(",") { escape(it) })
            append("\r\n")
        }
    }

    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
