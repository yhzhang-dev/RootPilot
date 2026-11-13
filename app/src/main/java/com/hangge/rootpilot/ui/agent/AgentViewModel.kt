package com.hangge.rootpilot.ui.agent

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.prefs.TokenStore
import com.hangge.rootpilot.data.repository.HistoryRepository
import com.hangge.rootpilot.domain.model.AppSettings
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.provider.RootPilotProvider
import com.hangge.rootpilot.service.AgentServer
import com.hangge.rootpilot.service.AgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpProbe(
    val label: String,
    val path: String,
    val status: Int,
    val latencyMs: Long,
    val body: String,
)

data class SelfTestResult(val probes: List<HttpProbe>, val finishedAt: Long)

data class ProviderCheck(
    val snapshots: Int,
    val actions: Int,
    val error: String? = null,
)

data class AgentUiState(
    val settings: AppSettings = AppSettings(),
    val running: Boolean = false,
    val boundPort: Int = 0,
    val serverError: String? = null,
    val maskedToken: String = "",
    val portInput: String = AppSettings.DEFAULT_AGENT_PORT.toString(),
    val portError: String? = null,
    val requests: List<ApiRequestLog> = emptyList(),
    val selfTest: SelfTestResult? = null,
    val testing: Boolean = false,
    val providerCheck: ProviderCheck? = null,
    val checkingProvider: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class AgentViewModel(
    private val application: Application,
    private val settingsStore: SettingsStore,
    private val tokenStore: TokenStore,
    private val agentServer: AgentServer,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsStore.settings,
                agentServer.running,
                agentServer.boundPort,
                agentServer.lastError,
                historyRepository.apiRequests,
            ) { settings, running, boundPort, serverError, requests ->
                AgentUiStateSlice(settings, running, boundPort, serverError, requests)
            }.collect { slice ->
                _state.update {
                    it.copy(
                        settings = slice.settings,
                        running = slice.running,
                        boundPort = slice.boundPort,
                        serverError = slice.serverError,
                        requests = slice.requests,
                        message = if (slice.running && it.message?.startsWith("正在启动") == true) {
                            null
                        } else {
                            it.message
                        },
                        portInput = if (it.portInput == AppSettings.DEFAULT_AGENT_PORT.toString()) {
                            slice.settings.agentPort.toString()
                        } else {
                            it.portInput
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            val masked = withContext(Dispatchers.IO) { runCatching { tokenStore.masked() }.getOrDefault("") }
            _state.update { it.copy(maskedToken = masked) }
        }
    }

    fun startService() {
        _state.update { it.copy(error = null, message = "正在启动 Agent 服务…") }
        runCatching { AgentService.start(application) }
            .onFailure { error ->
                _state.update { it.copy(error = "启动失败：${error.message}", message = null) }
            }
    }

    fun stopService() {
        runCatching { AgentService.stop(application) }
            .onFailure { error -> _state.update { it.copy(error = "停止失败：${error.message}") } }
    }

    fun savePort(input: String) {
        _state.update { it.copy(portInput = input, portError = null) }
    }

    fun commitPort() {
        val port = _state.value.portInput.trim().toIntOrNull()
        if (port == null || port !in AppSettings.PORT_RANGE) {
            _state.update { it.copy(portError = "端口需为 1024–65535 的整数") }
            return
        }
        settingsStore.setAgentPort(port)
        _state.update {
            it.copy(
                portError = null,
                message = "端口已保存为 $port；重启服务后生效",
            )
        }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            val masked = withContext(Dispatchers.IO) {
                tokenStore.regenerate()
                tokenStore.masked()
            }
            _state.update { it.copy(maskedToken = masked, message = "Token 已重新生成，旧 Token 立即失效") }
        }
    }

    fun copyToken(): String? {
        val token = runCatching { tokenStore.token() }.getOrNull()
        _state.update { it.copy(message = if (token != null) "Token 已复制到剪贴板" else "无法读取 Token") }
        return token
    }

    fun selfTest() {
        if (_state.value.testing) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true, error = null, message = null) }
            val token = runCatching { tokenStore.token() }.getOrNull()
            val probes = withContext(Dispatchers.IO) {
                listOf(
                    probe("健康检查（无 Token）", "/v1/health", token = null),
                    probe("状态（带 Token）", "/v1/status", token = token),
                    probe("能力发现（带 Token）", "/v1/capabilities", token = token),
                    probe("网络信息（带 Token）", "/v1/network/status", token = token),
                    probe("传感器清单（带 Token）", "/v1/sensors", token = token),
                    probe("加速度计采样（带 Token）", "/v1/sensors/sample?type=1", token = token),
                    probe("最近定位（带 Token）", "/v1/location/current", token = token),
                    probe("UI 层级（带 Token）", "/v1/ui/tree", token = token),
                    probe("文件目录（带 Token）", "/v1/files?path=", token = token),
                    probe("屏幕截图（带 Token）", "/v1/screen/screenshot", token = token),
                    probe("通知监听（带 Token）", "/v1/notifications", token = token),
                    probe(
                        "启动加速度订阅（带 Token）",
                        "/v1/subscriptions/sensor/start",
                        token = token,
                        method = "POST",
                        jsonBody = """{"type":1,"intervalMs":500}""",
                    ),
                    probe(
                        "启动定位订阅（带 Token）",
                        "/v1/subscriptions/location/start",
                        token = token,
                        method = "POST",
                        jsonBody = """{"intervalMs":2000}""",
                    ),
                    probe("事件流（带 Token）", "/v1/events?after=0&timeoutMs=2000", token = token),
                    probe("订阅状态（带 Token）", "/v1/subscriptions", token = token),
                    probe(
                        "停止加速度订阅（带 Token）",
                        "/v1/subscriptions/sensor/stop",
                        token = token,
                        method = "POST",
                        jsonBody = """{"type":1}""",
                    ),
                    probe(
                        "停止定位订阅（带 Token）",
                        "/v1/subscriptions/location/stop",
                        token = token,
                        method = "POST",
                        jsonBody = "{}",
                    ),
                    probe("自动化观察（带 Token）", "/v1/automation/observe", token = token),
                    probe("状态（无 Token，应 401）", "/v1/status", token = null),
                )
            }
            _state.update {
                it.copy(
                    testing = false,
                    selfTest = SelfTestResult(probes, System.currentTimeMillis()),
                )
            }
        }
    }

    fun providerSelfTest() {
        if (_state.value.checkingProvider) return
        viewModelScope.launch {
            _state.update { it.copy(checkingProvider = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ProviderCheck(
                        snapshots = countRows(RootPilotProvider.snapshotsUri()),
                        actions = countRows(RootPilotProvider.actionsUri()),
                    )
                }.getOrElse { error ->
                    ProviderCheck(0, 0, error.message ?: "ContentResolver 查询失败")
                }
            }
            _state.update { it.copy(checkingProvider = false, providerCheck = result) }
        }
    }

    fun consumeMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun countRows(uri: android.net.Uri): Int =
        application.contentResolver.query(uri, null, null, null, null)?.use { it.count } ?: 0

    private fun probe(
        label: String,
        path: String,
        token: String?,
        method: String = "GET",
        jsonBody: String? = null,
    ): HttpProbe {
        val startedAt = System.currentTimeMillis()
        var status = 0
        var body = ""
        val port = settingsStore.current().agentPort
        try {
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3_000
                // UIAutomator hierarchy capture can legitimately take several seconds on OEM ROMs.
                // Keep the self-test client alive long enough for the combined screenshot + UI tree
                // observation instead of disconnecting while the server is still writing its result.
                readTimeout = if (path.startsWith("/v1/automation/observe")) 15_000 else 3_000
                if (token != null) setRequestProperty("X-RootPilot-Token", token)
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (jsonBody != null) {
                    connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                }
                status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                body = stream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(400)
                    val count = reader.read(buffer)
                    if (count > 0) String(buffer, 0, count) else ""
                }.orEmpty()
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            body = "连接失败：${error.message ?: error.javaClass.simpleName}"
        }
        return HttpProbe(
            label = label,
            path = path,
            status = status,
            latencyMs = System.currentTimeMillis() - startedAt,
            body = body,
        )
    }

    private data class AgentUiStateSlice(
        val settings: AppSettings,
        val running: Boolean,
        val boundPort: Int,
        val serverError: String?,
        val requests: List<ApiRequestLog>,
    )

    companion object {
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer {
                AgentViewModel(
                    application = context.applicationContext as Application,
                    settingsStore = container.settingsStore,
                    tokenStore = container.tokenStore,
                    agentServer = container.agentServer,
                    historyRepository = container.historyRepository,
                )
            }
        }
    }
}
