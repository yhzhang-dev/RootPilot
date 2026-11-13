package com.hangge.rootpilot.service

import com.hangge.rootpilot.data.local.ApiRequestDao
import com.hangge.rootpilot.data.local.ApiRequestEntity
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.prefs.TokenStore
import com.hangge.rootpilot.data.repository.AppRepository
import com.hangge.rootpilot.data.repository.DeviceRepository
import com.hangge.rootpilot.data.repository.HistoryRepository
import com.hangge.rootpilot.data.root.PackageNames
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.data.system.AgentDeviceInfoSource
import com.hangge.rootpilot.data.system.AgentFileStore
import com.hangge.rootpilot.data.system.AgentLocationSource
import com.hangge.rootpilot.domain.ActionErrorCode
import com.hangge.rootpilot.domain.ActionExecutor
import com.hangge.rootpilot.domain.DeviceAgentController
import com.hangge.rootpilot.domain.model.ActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Loopback-only HTTP API. One request per connection, JSON via org.json, no free-form shell.
 * Every request is logged to Room (method/path/status/duration) and never logs headers or bodies.
 */
class AgentServer(
    private val settings: SettingsStore,
    private val tokenStore: TokenStore,
    private val deviceRepository: DeviceRepository,
    private val appRepository: AppRepository,
    private val historyRepository: HistoryRepository,
    private val actionExecutor: ActionExecutor,
    private val apiRequestDao: ApiRequestDao,
    private val rootShell: RootShell,
    private val deviceInfo: AgentDeviceInfoSource,
    private val deviceController: DeviceAgentController,
    private val fileStore: AgentFileStore,
    private val locationSource: AgentLocationSource,
    private val eventHub: AgentEventHub,
    private val notificationCenter: AgentNotificationCenter,
    private val telemetry: AgentTelemetryManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    suspend fun start(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext Result.success(Unit)
        runCatching {
            // Loopback unless the owner opted into LAN access (AppSettings.lanAccess).
            val host = if (settings.current().lanAccess) ANY_ADDRESS else LOOPBACK
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(host), port))
            }
            serverSocket = socket
            _boundPort.value = socket.localPort
            _lastError.value = null
            _running.value = true
            acceptJob = scope.launch { acceptLoop(socket) }
        }.onFailure { error ->
            _running.value = false
            _boundPort.value = 0
            _lastError.value = "启动失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _boundPort.value = 0
        _running.value = false
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (currentCoroutineContext().isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            scope.launch { client.use { handleConnection(it) } }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        val startedAt = System.currentTimeMillis()
        var method = "?"
        var path = "/"
        var status = 500
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()

            val requestLine = readLine(input)
            if (requestLine.isNullOrBlank()) {
                writeResponse(output, 400, errorBody("无效请求行", "COMMAND_FAILED"))
                status = 400
                return
            }
            val parts = requestLine.split(' ')
            method = parts.getOrNull(0)?.uppercase() ?: "?"
            val target = parts.getOrNull(1) ?: "/"
            path = target.substringBefore('?').ifBlank { "/" }
            val headers = readHeaders(input)
            val contentLength = headers["content-length"]?.toIntOrNull()
                ?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = readBody(input, contentLength)

            val result = route(method, path, target, headers, body)
            status = result.status
            writeResponse(output, result.status, result.body)
        } catch (error: Exception) {
            status = 500
            runCatching {
                writeResponse(
                    socket.getOutputStream(),
                    500,
                    errorBody("服务器内部错误：${error.message ?: "unknown"}", "COMMAND_FAILED"),
                )
            }
        } finally {
            runCatching { socket.close() }
            logRequest(method, path, status, System.currentTimeMillis() - startedAt)
        }
    }

    private suspend fun route(
        method: String,
        path: String,
        target: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResult {
        if (method == "GET" && path == "/v1/health") {
            return HttpResult(200, JSONObject().put("ok", true).put("version", "2").toString())
        }
        if (!isAuthorized(headers)) {
            return HttpResult(401, errorBody("Token 无效", "UNAUTHORIZED"))
        }

        return when {
            method == "GET" && path == "/v1/status" -> HttpResult(200, statusJson().toString())
            method == "GET" && path == "/v1/capabilities" -> HttpResult(200, capabilitiesJson().toString())
            method == "GET" && path == "/v1/network/status" -> HttpResult(200, networkJson().toString())
            method == "GET" && path == "/v1/sensors" -> HttpResult(200, sensorsJson().toString())
            method == "GET" && path == "/v1/sensors/sample" -> sensorSampleResult(parseQuery(target))
            method == "GET" && path == "/v1/location/current" -> locationResult()
            method == "GET" && path == "/v1/screen/screenshot" -> screenshotResult()
            method == "GET" && path == "/v1/ui/tree" -> uiTreeResult()
            method == "GET" && path == "/v1/ui/windows" -> windowsResult()
            method == "GET" && path == "/v1/files" -> filesResult(parseQuery(target))
            method == "GET" && path == "/v1/files/read" -> fileReadResult(parseQuery(target))
            method == "GET" && path == "/v1/notifications" -> notificationsResult()
            method == "GET" && path == "/v1/events" -> eventsResult(parseQuery(target))
            method == "GET" && path == "/v1/subscriptions" -> HttpResult(200, telemetry.status().toString())
            method == "GET" && path == "/v1/automation/observe" -> observationResult(parseQuery(target))
            method == "GET" && path == "/v1/ui/foreground" -> foregroundResult()
            method == "GET" && path == "/v1/apps" -> HttpResult(200, appsJson(parseQuery(target)).toString())
            method == "GET" && path == "/v1/history" -> HttpResult(200, historyJson(parseQuery(target)).toString())
            method == "POST" && path == "/v1/ui/tap" -> handleUiTap(body)
            method == "POST" && path == "/v1/ui/node/tap" -> handleUiNodeTap(body)
            method == "POST" && path == "/v1/ui/swipe" -> handleUiSwipe(body)
            method == "POST" && path == "/v1/ui/key" -> handleUiKey(body)
            method == "POST" && path == "/v1/ui/text" -> handleUiText(body)
            method == "POST" && path == "/v1/files/write" -> handleFileWrite(body)
            method == "POST" && path == "/v1/notifications/dismiss" -> handleNotificationDismiss(body)
            method == "POST" && path == "/v1/subscriptions/location/start" -> handleLocationSubscription(body, true)
            method == "POST" && path == "/v1/subscriptions/location/stop" -> handleLocationSubscription(body, false)
            method == "POST" && path == "/v1/subscriptions/sensor/start" -> handleSensorSubscription(body, true)
            method == "POST" && path == "/v1/subscriptions/sensor/stop" -> handleSensorSubscription(body, false)
            method == "POST" && path == "/v1/automation/act" -> handleAutomationAct(body)
            method == "POST" && path.startsWith("/v1/apps/") -> handleWriteAction(path, parseQuery(target))
            else -> HttpResult(404, errorBody("未知路径：$path", "NOT_FOUND"))
        }
    }

    private suspend fun handleWriteAction(path: String, query: Map<String, String>): HttpResult {
        val segments = path.split('/').filter { it.isNotBlank() }
        // v1 / apps / {packageName} / {action}
        if (segments.size != 4 || segments[0] != "v1" || segments[1] != "apps") {
            return HttpResult(404, errorBody("未知路径：$path", "NOT_FOUND"))
        }
        val packageName = runCatching { URLDecoder.decode(segments[2], "UTF-8") }.getOrDefault(segments[2])
        val actionName = segments[3]
        if (!PackageNames.isValid(packageName)) {
            return HttpResult(400, errorBody("包名无效：$packageName", "INVALID_PACKAGE"))
        }
        if (actionName == "launch") {
            if (!settings.current().agentControl) {
                return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
            }
            return actionResult(
                deviceController.launchApp(packageName, query["user"]?.toIntOrNull()),
            )
        }
        if (actionName !in WRITE_ACTIONS) {
            return HttpResult(404, errorBody("未知操作：$actionName", "NOT_FOUND"))
        }

        if (!settings.current().agentControl) {
            return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
        }
        val action = WRITE_ACTIONS.getValue(actionName)
        if (action != ActionType.UNFREEZE && settings.current().safeMode) {
            return HttpResult(403, errorBody("SafeMode 已开启", "SAFE_MODE"))
        }

        val result = when (action) {
            ActionType.FREEZE -> actionExecutor.freeze(packageName)
            ActionType.UNFREEZE -> actionExecutor.unfreeze(packageName)
            ActionType.FORCE_STOP -> actionExecutor.forceStop(packageName)
            else -> return HttpResult(500, errorBody("不支持的操作", "COMMAND_FAILED"))
        }
        return if (result.success) {
            HttpResult(200, JSONObject().put("ok", true).put("packageName", packageName).toString())
        } else {
            HttpResult(
                statusFor(result.code),
                errorBody(result.message, result.code?.name ?: "COMMAND_FAILED"),
            )
        }
    }

    private suspend fun capabilitiesJson(): JSONObject {
        val permissions = JSONObject()
        deviceInfo.permissionStates().forEach { (name, granted) -> permissions.put(name, granted) }
        return JSONObject()
            .put("apiVersion", 2)
            .put("transport", if (settings.current().lanAccess) "lan-http" else "loopback-adb-forward")
            .put("root", rootShell.isRootAvailable())
            .put("agentControl", settings.current().agentControl)
            .put("safeMode", settings.current().safeMode)
            .put("lanAccess", settings.current().lanAccess)
            .put("requireToken", settings.current().requireToken)
            .put("permissions", permissions)
            .put(
                "accessibility",
                JSONObject()
                    .put("connected", RootPilotAccessibilityService.isConnected())
                    .put(
                        "uiTreeSource",
                        if (RootPilotAccessibilityService.isConnected()) "accessibility" else "uiautomator",
                    )
                    .put("canTapNode", RootPilotAccessibilityService.isConnected()),
            )
            .put(
                "readTools",
                JSONArray(
                    listOf(
                        "status", "apps", "history", "network", "sensors", "sensorSample",
                        "location", "foregroundApp", "screenshot", "uiTree", "files",
                        "notifications", "events", "subscriptions", "automationObserve",
                    ),
                ),
            )
            .put(
                "writeTools",
                JSONArray(
                    listOf(
                        "launchApp", "freeze", "unfreeze", "forceStop", "tap", "swipe",
                        "keyEvent", "inputText", "fileWrite", "notificationDismiss",
                        "locationSubscription", "sensorSubscription", "automationAct", "tapNode",
                    ),
                ),
            )
    }

    private fun networkJson(): JSONObject {
        val network = deviceInfo.network()
        return JSONObject()
            .put("connected", network.transports.isNotEmpty())
            .put("transports", JSONArray(network.transports))
            .put("metered", network.metered)
            .put("validated", network.validated)
            .put("downstreamKbps", network.downstreamKbps)
            .put("upstreamKbps", network.upstreamKbps)
            .put("addresses", JSONArray(network.addresses))
    }

    private fun sensorsJson(): JSONObject {
        val items = JSONArray()
        deviceInfo.sensors().forEach { sensor ->
            items.put(
                JSONObject()
                    .put("name", sensor.name)
                    .put("type", sensor.type)
                    .put("vendor", sensor.vendor)
                    .put("version", sensor.version)
                    .put("powerMilliamp", sensor.powerMilliamp)
                    .put("resolution", sensor.resolution)
                    .put("wakeUp", sensor.wakeUp),
            )
        }
        return JSONObject().put("items", items).put("total", items.length())
    }

    private suspend fun sensorSampleResult(params: Map<String, String>): HttpResult = try {
        val type = params["type"]?.toIntOrNull() ?: throw IllegalArgumentException("缺少整数 type")
        val timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 3_000L
        val sample = deviceInfo.sampleSensor(type, timeoutMs)
        HttpResult(
            200,
            JSONObject()
                .put("type", sample.type)
                .put("name", sample.name)
                .put("values", JSONArray(sample.values))
                .put("accuracy", sample.accuracy)
                .put("capturedAtNanos", sample.capturedAtNanos)
                .toString(),
        )
    } catch (error: Exception) {
        HttpResult(400, errorBody(error.message ?: "传感器采样失败", "SENSOR_UNAVAILABLE"))
    }

    private suspend fun locationResult(): HttpResult = try {
        val location = locationSource.current()
        HttpResult(
            200,
            JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracyMeters)
                .put("altitudeMeters", location.altitudeMeters ?: JSONObject.NULL)
                .put("provider", location.provider)
                .put("capturedAt", location.capturedAt)
                .toString(),
        )
    } catch (error: Exception) {
        HttpResult(409, errorBody(error.message ?: "定位不可用", "LOCATION_UNAVAILABLE"))
    }

    private suspend fun screenshotResult(): HttpResult = try {
        val bytes = deviceController.captureScreen()
        HttpResult(
            200,
            JSONObject()
                .put("mimeType", "image/png")
                .put("size", bytes.size)
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString(),
        )
    } catch (error: Exception) {
        HttpResult(409, errorBody(error.message ?: "截图失败", "SCREEN_CAPTURE_FAILED"))
    }

    private suspend fun uiTreeResult(): HttpResult = try {
        val (xml, source) = dumpTree()
        HttpResult(
            200,
            JSONObject().put("format", "uiautomator-xml").put("source", source).put("xml", xml).toString(),
        )
    } catch (error: Exception) {
        HttpResult(409, errorBody(error.message ?: "UI 层级采集失败", "UI_TREE_FAILED"))
    }

    /**
     * Accessibility tree when the bridge is connected, otherwise the rooted uiautomator dump.
     * The accessibility path returns in milliseconds and does not depend on an idle main thread,
     * which is what made the combined observation slow.
     */
    private suspend fun dumpTree(): Pair<String, String> {
        val fromAccessibility = deviceController.accessibilityTree()?.takeIf { it.length > 32 }
        if (fromAccessibility != null) return fromAccessibility to "accessibility"
        return deviceController.dumpUiHierarchy() to "uiautomator"
    }

    /** Foreground package plus its Android user, so a cloned app is distinguishable. */
    private suspend fun foregroundResult(): HttpResult {
        val info = deviceController.foregroundApp()
        return HttpResult(
            200,
            JSONObject()
                .put("packageName", info?.first ?: JSONObject.NULL)
                .put("userId", info?.second ?: JSONObject.NULL)
                .toString(),
        )
    }

    /**
     * Window geometry. The IME is a separate window that sits above the app and swallows touches
     * in its band, which is why a coordinate tap near the bottom of a chat screen can do nothing.
     *
     * `userId` is only filled for the focused/active application window, taken from the resumed
     * activity: a cloned app (分身) and its original share the package name and can both have
     * windows alive at once, so attributing a window by package name alone would be ambiguous.
     */
    private suspend fun windowsResult(): HttpResult {
        val foreground = runCatching { deviceController.foregroundApp() }.getOrNull()
        val items = JSONArray()
        deviceController.windowSnapshots().forEach { window ->
            val attributedUser =
                if (foreground != null &&
                    window.packageName == foreground.first &&
                    (window.focused || window.active)
                ) {
                    foreground.second
                } else {
                    null
                }
            items.put(windowJson(window, attributedUser))
        }
        return HttpResult(
            200,
            JSONObject()
                .put("foregroundPackage", foreground?.first ?: JSONObject.NULL)
                .put("foregroundUserId", foreground?.second ?: JSONObject.NULL)
                .put("items", items)
                .put("total", items.length())
                .toString(),
        )
    }

    private fun windowJson(window: WindowSnapshot, userId: Int? = null): JSONObject = JSONObject()
        .put("type", window.type)
        .put("typeName", window.typeName)
        .put("packageName", window.packageName ?: JSONObject.NULL)
        .put("userId", userId ?: JSONObject.NULL)
        .put(
            "boundsText",
            "[${window.boundsLeft},${window.boundsTop}][${window.boundsRight},${window.boundsBottom}]",
        )
        .put("left", window.boundsLeft)
        .put("top", window.boundsTop)
        .put("right", window.boundsRight)
        .put("bottom", window.boundsBottom)
        .put("focused", window.focused)
        .put("active", window.active)

    private fun filesResult(params: Map<String, String>): HttpResult = try {
        val path = params["path"].orEmpty()
        val items = JSONArray()
        fileStore.list(path).forEach { file ->
            items.put(
                JSONObject()
                    .put("name", file.name)
                    .put("path", file.path)
                    .put("directory", file.directory)
                    .put("size", file.size)
                    .put("modifiedAt", file.modifiedAt),
            )
        }
        HttpResult(200, JSONObject().put("path", path).put("items", items).toString())
    } catch (error: Exception) {
        HttpResult(400, errorBody(error.message ?: "目录读取失败", "INVALID_PATH"))
    }

    private fun fileReadResult(params: Map<String, String>): HttpResult = try {
        val path = params["path"] ?: throw IllegalArgumentException("缺少 path")
        val bytes = fileStore.read(path)
        HttpResult(
            200,
            JSONObject()
                .put("path", path)
                .put("size", bytes.size)
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString(),
        )
    } catch (error: Exception) {
        HttpResult(400, errorBody(error.message ?: "文件读取失败", "INVALID_PATH"))
    }

    private suspend fun handleFileWrite(body: String): HttpResult {
        if (!settings.current().agentControl) {
            return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
        }
        return parseBody(body) { json ->
            val path = json.optString("path")
            if (path.isBlank()) throw IllegalArgumentException("path 不能为空")
            val encoded = json.optString("base64")
            if (encoded.isBlank()) throw IllegalArgumentException("base64 不能为空")
            val entry = fileStore.write(path, Base64.decode(encoded, Base64.DEFAULT))
            HttpResult(
                200,
                JSONObject().put("ok", true).put("path", entry.path).put("size", entry.size).toString(),
            )
        }
    }

    private fun notificationsResult(): HttpResult {
        val items = JSONArray()
        notificationCenter.notifications.value.forEach { item ->
            items.put(
                JSONObject()
                    .put("key", item.key)
                    .put("packageName", item.packageName)
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("postedAt", item.postedAt)
                    .put("ongoing", item.ongoing)
                    .put("clearable", item.clearable),
            )
        }
        return HttpResult(200, JSONObject().put("items", items).put("total", items.length()).toString())
    }

    private suspend fun eventsResult(params: Map<String, String>): HttpResult {
        val after = params["after"]?.toLongOrNull() ?: 0L
        val timeout = params["timeoutMs"]?.toLongOrNull() ?: 25_000L
        val events = eventHub.poll(after, timeout)
        val items = JSONArray()
        events.forEach { event ->
            items.put(
                JSONObject()
                    .put("id", event.id)
                    .put("type", event.type)
                    .put("createdAt", event.createdAt)
                    .put("data", JSONObject(event.data)),
            )
        }
        return HttpResult(200, JSONObject().put("items", items).put("lastEventId", events.lastOrNull()?.id ?: after).toString())
    }

    private suspend fun handleNotificationDismiss(body: String): HttpResult {
        if (!settings.current().agentControl) return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
        return parseBody(body) { json ->
            val key = json.optString("key")
            if (key.isBlank()) throw IllegalArgumentException("key 不能为空")
            val listener = AgentNotificationListenerService.instance
                ?: return@parseBody HttpResult(409, errorBody("通知监听服务未连接", "NOTIFICATION_ACCESS_OFF"))
            listener.cancelNotification(key)
            HttpResult(200, JSONObject().put("ok", true).put("key", key).toString())
        }
    }

    private suspend fun handleLocationSubscription(body: String, start: Boolean): HttpResult {
        if (!settings.current().agentControl) return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
        return parseBody(body) { json ->
            if (start) {
                val interval = telemetry.startLocation(json.optLong("intervalMs", 10_000))
                HttpResult(200, JSONObject().put("ok", true).put("intervalMs", interval).toString())
            } else {
                telemetry.stopLocation()
                HttpResult(200, JSONObject().put("ok", true).toString())
            }
        }
    }

    private suspend fun handleSensorSubscription(body: String, start: Boolean): HttpResult {
        if (!settings.current().agentControl) return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
        return parseBody(body) { json ->
            val type = json.requireInt("type")
            if (start) {
                val interval = telemetry.startSensor(type, json.optLong("intervalMs", 1_000))
                HttpResult(200, JSONObject().put("ok", true).put("type", type).put("intervalMs", interval).toString())
            } else {
                telemetry.stopSensor(type)
                HttpResult(200, JSONObject().put("ok", true).put("type", type).toString())
            }
        }
    }

    private suspend fun observationResult(params: Map<String, String>): HttpResult = try {
        HttpResult(200, observationJson(params).toString())
    } catch (error: Exception) {
        HttpResult(409, errorBody(error.message ?: "观察失败", "OBSERVATION_FAILED"))
    }

    /**
     * One observation. `screenshot=0` or `ui=0` skips the expensive half (a caller that only needs
     * control bounds should ask for `ui` alone); each half is time-boxed so a wedged `uiautomator`
     * can never hang the whole request, and a failed half comes back as a warning instead.
     */
    private suspend fun observationJson(params: Map<String, String> = emptyMap()): JSONObject {
        fun flag(name: String): Boolean = when (params[name]?.lowercase()) {
            null, "", "1", "true", "yes" -> true
            else -> false
        }

        val warnings = JSONArray()
        var screenshot: ByteArray? = null
        var screenshotMime = "image/png"
        var xml: String? = null
        var uiSource = "uiautomator"

        if (flag("screenshot")) {
            val raw = runCatching { deviceController.captureScreen() }.getOrNull()
            if (raw == null) {
                warnings.put("screenshot unavailable")
            } else {
                val encoded = encodeScreenshot(raw)
                screenshot = encoded.first
                screenshotMime = encoded.second
            }
        }
        if (flag("ui")) {
            val tree = runCatching { dumpTree() }.getOrNull()
            xml = tree?.first
            if (tree != null) uiSource = tree.second
            if (xml == null) warnings.put("ui hierarchy unavailable")
        }

        val occluders = JSONArray()
        runCatching {
            deviceController.windowSnapshots()
                .filter { it.type != AccessibilityWindowInfo.TYPE_APPLICATION }
                .forEach { occluders.put(windowJson(it)) }
        }

        val foreground = runCatching { deviceController.foregroundApp() }.getOrNull()

        return JSONObject()
            .put("capturedAt", System.currentTimeMillis())
            .put("foregroundPackage", foreground?.first ?: JSONObject.NULL)
            .put("foregroundUserId", foreground?.second ?: JSONObject.NULL)
            .put("screenshotMimeType", screenshotMime)
            .put("screenshotBase64", screenshot?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
            .put("uiFormat", "uiautomator-xml")
            .put("uiXml", xml ?: JSONObject.NULL)
            .put("uiSource", if (xml == null) JSONObject.NULL else uiSource)
            .put("occluders", occluders)
            .put("warnings", warnings)
    }

    /**
     * A full-resolution PNG is ~1.5 MB (base64 ~2 MB), which does not fit in the window a
     * frozen app is given before the OEM freezer suspends it again. Downscaling to a JPEG keeps
     * one observation at a few hundred KB without losing the information an agent needs.
     */
    private fun encodeScreenshot(png: ByteArray): Pair<ByteArray, String> {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()
            ?: return png to "image/png"
        return runCatching {
            val scale = minOf(1f, SCREENSHOT_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
            val scaled = if (scale < 0.99f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray() to "image/jpeg"
        }.getOrElse { png to "image/png" }.also { bitmap.recycle() }
    }

    private suspend fun handleAutomationAct(body: String): HttpResult {
        if (!settings.current().agentControl) return HttpResult(403, errorBody("未开启 Agent 控制", "AGENT_CONTROL_OFF"))
        return parseBody(body) { json ->
            val action = json.optString("action")
            val result = when (action) {
                "tap" -> deviceController.tap(json.requireInt("x"), json.requireInt("y"), json.optInt("holdMs", 0))
                "swipe" -> deviceController.swipe(
                    json.requireInt("x1"), json.requireInt("y1"),
                    json.requireInt("x2"), json.requireInt("y2"),
                    json.optInt("durationMs", 300),
                )
                "key" -> deviceController.keyEvent(json.requireInt("keyCode"))
                "text" -> deviceController.inputText(json.optString("text"))
                "launch" -> deviceController.launchApp(
                    json.optString("packageName"),
                    json.optInt("userId", -1).takeIf { it >= 0 },
                )
                "node" -> deviceController.tapNode(json.requireInt("nodeIndex"))
                "clipboard" -> deviceController.setClipboard(json.optString("text"))
                "paste" -> deviceController.paste()
                else -> throw IllegalArgumentException("不支持的 action：$action")
            }
            if (!result.success) return@parseBody actionResult(result)
            eventHub.publish("automation.action", JSONObject().put("action", action).put("message", result.message))
            val response = JSONObject().put("ok", true).put("message", result.message)
            if (json.optBoolean("observe", true)) {
                delay(json.optLong("settleMs", 350).coerceIn(0, 3_000))
                response.put(
                    "observation",
                    observationJson(
                        mapOf(
                            "screenshot" to json.optBoolean("screenshot", true).toString(),
                            "ui" to json.optBoolean("ui", true).toString(),
                        ),
                    ),
                )
            }
            HttpResult(200, response.toString())
        }
    }

    private suspend fun handleUiTap(body: String): HttpResult = parseBody(body) { json ->
        actionResult(
            deviceController.tap(
                json.requireInt("x"),
                json.requireInt("y"),
                json.optInt("holdMs", 0),
            ),
        )
    }

    /** Node-addressed tap through the accessibility bridge: no root, no blind coordinates. */
    private suspend fun handleUiNodeTap(body: String): HttpResult = parseBody(body) { json ->
        val nodeIndex = json.optInt("nodeIndex", -1)
        if (nodeIndex < 0) throw IllegalArgumentException("缺少 nodeIndex")
        actionResult(deviceController.tapNode(nodeIndex))
    }

    private suspend fun handleUiSwipe(body: String): HttpResult = parseBody(body) { json ->
        actionResult(
            deviceController.swipe(
                json.requireInt("x1"), json.requireInt("y1"),
                json.requireInt("x2"), json.requireInt("y2"),
                json.optInt("durationMs", 300),
            ),
        )
    }

    private suspend fun handleUiKey(body: String): HttpResult = parseBody(body) { json ->
        actionResult(deviceController.keyEvent(json.requireInt("keyCode")))
    }

    private suspend fun handleUiText(body: String): HttpResult = parseBody(body) { json ->
        val text = json.optString("text")
        if (text.isBlank()) throw IllegalArgumentException("text 不能为空")
        actionResult(deviceController.inputText(text))
    }

    private suspend fun parseBody(body: String, block: suspend (JSONObject) -> HttpResult): HttpResult = try {
        block(JSONObject(body.ifBlank { "{}" }))
    } catch (error: Exception) {
        HttpResult(400, errorBody("参数无效：${error.message}", "INVALID_ARGUMENT"))
    }

    private fun JSONObject.requireInt(name: String): Int {
        if (!has(name)) throw IllegalArgumentException("缺少 $name")
        return getInt(name)
    }

    private fun actionResult(result: com.hangge.rootpilot.domain.ActionResult): HttpResult =
        if (result.success) {
            HttpResult(200, JSONObject().put("ok", true).put("message", result.message).toString())
        } else {
            HttpResult(statusFor(result.code), errorBody(result.message, result.code?.name ?: "COMMAND_FAILED"))
        }

    private suspend fun statusJson(): JSONObject {
        val profile = deviceRepository.profile()
        val battery = deviceRepository.battery.value
        val latest = deviceRepository.latestOnce()
        val rootAvailable = rootShell.isRootAvailable()
        return JSONObject()
            .put("device", profile.model)
            .put("manufacturer", profile.manufacturer)
            .put("android", profile.androidRelease)
            .put("sdk", profile.sdkInt)
            .put("root", rootAvailable)
            .put("battery", battery.percent ?: JSONObject.NULL)
            .put("charging", battery.charging ?: JSONObject.NULL)
            .put("batteryTemperatureC", battery.temperatureC ?: JSONObject.NULL)
            .put("memoryFreeBytes", latest?.memoryFreeBytes ?: 0L)
            .put("storageFreeBytes", latest?.storageFreeBytes ?: 0L)
            .put("appCount", profile.appCount)
            .put("capturedAt", latest?.capturedAt ?: 0L)
            .put("port", if (_boundPort.value != 0) _boundPort.value else settings.current().agentPort)
            .put("lanAccess", settings.current().lanAccess)
            .put("requireToken", settings.current().requireToken)
            .put("lanIp", lanIp() ?: JSONObject.NULL)
    }

    /** Wi-Fi IPv4 first, then any other non-loopback IPv4, so a desktop can find the phone. */
    private fun lanIp(): String? = runCatching {
        val entries = deviceInfo.network().addresses
        fun ipv4Of(entry: String): String? =
            entry.substringAfter(':', "").takeIf { it.isNotBlank() && !it.startsWith("127.") && !it.contains(':') }
        entries.firstNotNullOfOrNull { entry -> ipv4Of(entry)?.takeIf { entry.startsWith("wlan") } }
            ?: entries.firstNotNullOfOrNull { entry -> ipv4Of(entry) }
    }.getOrNull()

    private suspend fun appsJson(params: Map<String, String>): JSONObject {
        val query = params["query"].orEmpty()
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val apps = appRepository.loadApps()
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .take(limit)
        val items = JSONArray()
        apps.forEach { app ->
            items.put(
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("label", app.label)
                    .put("versionName", app.versionName)
                    .put("targetSdk", app.targetSdk)
                    .put("isSystem", app.isSystem)
                    .put("isEnabled", app.isEnabled)
                    .put("permissions", JSONArray(app.permissions.map { it.name })),
            )
        }
        return JSONObject().put("items", items).put("total", apps.size)
    }

    private suspend fun historyJson(params: Map<String, String>): JSONObject {
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val actions = historyRepository.recentActions(limit)
        val items = JSONArray()
        actions.forEach { action ->
            items.put(
                JSONObject()
                    .put("createdAt", action.createdAt)
                    .put("packageName", action.packageName ?: JSONObject.NULL)
                    .put("action", action.action.name)
                    .put("success", action.success)
                    .put("message", action.message),
            )
        }
        return JSONObject().put("items", items).put("total", actions.size)
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        // Token checking is opt-out for a trusted private LAN (AppSettings.requireToken).
        if (!settings.current().requireToken) return true
        val provided = headers[HEADER_TOKEN] ?: return false
        val expected = tokenStore.token()
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun logRequest(method: String, path: String, status: Int, durationMs: Long) {
        runCatching {
            apiRequestDao.insert(
                ApiRequestEntity(
                    createdAt = System.currentTimeMillis(),
                    method = method,
                    path = path,
                    statusCode = status,
                    durationMs = durationMs,
                ),
            )
        }
    }

    private fun statusFor(code: ActionErrorCode?): Int = when (code) {
        ActionErrorCode.INVALID_PACKAGE -> 400
        ActionErrorCode.NOT_FOUND -> 404
        ActionErrorCode.SYSTEM_APP, ActionErrorCode.SAFE_MODE, ActionErrorCode.AGENT_CONTROL_OFF -> 403
        ActionErrorCode.ROOT_UNAVAILABLE -> 409
        ActionErrorCode.COMMAND_FAILED, null -> 500
    }

    private fun errorBody(message: String, code: String): String =
        JSONObject().put("error", message).put("code", code).toString()

    private fun parseQuery(target: String): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                .getOrDefault(pair.substringAfter('=', ""))
            key to value
        }.toMap()
    }

    private fun writeResponse(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${reasonPhrase(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var readAny = false
        while (true) {
            val next = input.read()
            if (next == -1) return if (readAny) buffer.toString("UTF-8") else null
            readAny = true
            if (next == '\n'.code) break
            if (buffer.size() >= MAX_LINE_BYTES) break
            if (next != '\r'.code) buffer.write(next)
        }
        return buffer.toString("UTF-8")
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        repeat(MAX_HEADERS) {
            val line = readLine(input) ?: return headers
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        else -> "Internal Server Error"
    }

    private data class HttpResult(val status: Int, val body: String)

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val ANY_ADDRESS = "0.0.0.0"
        private const val HEADER_TOKEN = "x-rootpilot-token"
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_BODY_BYTES = 6 * 1024 * 1024
        /** Longest side of a returned screenshot; keeps one observation small. */
        private const val SCREENSHOT_MAX_SIDE = 900
        private const val SCREENSHOT_QUALITY = 80
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 64
        private val WRITE_ACTIONS = mapOf(
            "freeze" to ActionType.FREEZE,
            "unfreeze" to ActionType.UNFREEZE,
            "force-stop" to ActionType.FORCE_STOP,
        )
    }
}
