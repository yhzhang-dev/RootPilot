package com.hangge.rootpilot.ui.dashboard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.prefs.TokenStore
import com.hangge.rootpilot.data.export.ReportWriter
import com.hangge.rootpilot.data.repository.DeviceRepository
import com.hangge.rootpilot.data.repository.HistoryRepository
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.AppSettings
import com.hangge.rootpilot.domain.model.BatteryState
import com.hangge.rootpilot.domain.model.DeviceProfile
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import com.hangge.rootpilot.service.AgentServer
import com.hangge.rootpilot.service.AgentService
import com.hangge.rootpilot.provider.RootPilotProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class CourseStepStatus { IDLE, RUNNING, SUCCESS, ERROR }

data class CourseDemoStep(
    val title: String,
    val chapter: String,
    val detail: String,
    val status: CourseStepStatus = CourseStepStatus.IDLE,
)

data class CourseDemoState(
    val running: Boolean = false,
    val steps: List<CourseDemoStep> = defaultCourseSteps(),
    val reportPath: String? = null,
    val finishedAt: Long? = null,
)

private fun defaultCourseSteps() = listOf(
    CourseDemoStep("数据持久化", "文件 / SharedPreferences / Room", "等待运行"),
    CourseDemoStep("数据共享", "ContentProvider", "等待运行"),
    CourseDemoStep("后台运行", "Service / 广播", "等待运行"),
    CourseDemoStep("本地联网", "HTTP / Socket", "等待运行"),
)

data class DashboardUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val capturing: Boolean = false,
    val profile: DeviceProfile? = null,
    val snapshot: DeviceSnapshot? = null,
    val battery: BatteryState = BatteryState(),
    val rootAvailable: Boolean? = null,
    val agentRunning: Boolean = false,
    val agentPort: Int = AppSettings.DEFAULT_AGENT_PORT,
    val lastAction: ActionRecord? = null,
    val message: String? = null,
    val error: String? = null,
    val courseDemo: CourseDemoState = CourseDemoState(),
)

class DashboardViewModel(
    private val application: Application,
    private val deviceRepository: DeviceRepository,
    private val rootShell: RootShell,
    private val agentServer: AgentServer,
    private val settingsStore: SettingsStore,
    private val historyRepository: HistoryRepository,
    private val tokenStore: TokenStore,
    private val reportWriter: ReportWriter,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                deviceRepository.latestSnapshot,
                deviceRepository.battery,
                historyRepository.actions,
            ) { snapshot, battery, actions ->
                Triple(snapshot, battery, actions.firstOrNull())
            }.collect { (snapshot, battery, lastAction) ->
                _state.update { current ->
                    current.copy(snapshot = snapshot, battery = battery, lastAction = lastAction)
                }
            }
        }
        viewModelScope.launch {
            combine(agentServer.running, settingsStore.settings) { running, settings ->
                running to settings.agentPort
            }.collect { (running, port) ->
                _state.update { current -> current.copy(agentRunning = running, agentPort = port) }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            val root = runCatching { rootShell.refreshRoot() }.getOrDefault(false)
            val profile = runCatching { deviceRepository.profile() }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    rootAvailable = root,
                    profile = profile ?: it.profile,
                )
            }
        }
    }

    fun captureSnapshot() {
        if (_state.value.capturing) return
        viewModelScope.launch {
            _state.update { it.copy(capturing = true, message = null, error = null) }
            runCatching {
                deviceRepository.captureSnapshot(rootAvailable = _state.value.rootAvailable == true)
            }.onSuccess { snapshot ->
                _state.update {
                    it.copy(
                        capturing = false,
                        snapshot = snapshot,
                        message = "快照已保存（${snapshot.appCount} 个应用）",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(capturing = false, error = "采集失败：${error.message}") }
            }
        }
    }

    fun runCourseDemo() {
        if (_state.value.courseDemo.running) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    message = null,
                    error = null,
                    courseDemo = CourseDemoState(running = true),
                )
            }
            val evidence = mutableListOf<JSONObject>()

            val storageOk = runCourseStep(0, "正在写入本地数据…") {
                val snapshot = deviceRepository.captureSnapshot(_state.value.rootAvailable == true)
                val settings = settingsStore.current()
                _state.update { it.copy(snapshot = snapshot) }
                val detail = "Room 已保存快照 #${snapshot.id}；SharedPreferences 端口 ${settings.agentPort}"
                evidence += JSONObject()
                    .put("stage", "storage")
                    .put("snapshotId", snapshot.id)
                    .put("agentPort", settings.agentPort)
                detail
            }
            if (!storageOk) return@launch finishCourseDemo(evidence)

            val providerOk = runCourseStep(1, "正在通过 ContentResolver 查询…") {
                val snapshots = countRows(RootPilotProvider.snapshotsUri())
                val actions = countRows(RootPilotProvider.actionsUri())
                evidence += JSONObject()
                    .put("stage", "provider")
                    .put("snapshots", snapshots)
                    .put("actions", actions)
                "Provider 返回 $snapshots 条快照、$actions 条操作记录"
            }
            if (!providerOk) return@launch finishCourseDemo(evidence)

            val serviceOk = runCourseStep(2, "正在启动前台服务…") {
                if (!agentServer.running.value) AgentService.start(application)
                val started = withTimeoutOrNull(4_000) { agentServer.running.first { it } } == true
                check(started) { agentServer.lastError.value ?: "Agent 服务启动超时" }
                val port = agentServer.boundPort.value
                evidence += JSONObject().put("stage", "service").put("port", port)
                "AgentService 运行中，Socket 监听 127.0.0.1:$port"
            }
            if (!serviceOk) return@launch finishCourseDemo(evidence)

            runCourseStep(3, "正在执行 HTTP 回环请求…") {
                val token = withContext(Dispatchers.IO) { tokenStore.token() }
                val port = agentServer.boundPort.value
                val health = withContext(Dispatchers.IO) { httpStatus(port, "/v1/health", null) }
                val status = withContext(Dispatchers.IO) { httpStatus(port, "/v1/status", token) }
                check(health == 200 && status == 200) { "HTTP 自检失败：health=$health，status=$status" }
                evidence += JSONObject()
                    .put("stage", "network")
                    .put("healthStatus", health)
                    .put("authenticatedStatus", status)
                "HTTP 200；Token 鉴权 200；请求已写入 API 日志"
            }
            finishCourseDemo(evidence)
        }
    }

    private suspend fun runCourseStep(
        index: Int,
        runningDetail: String,
        block: suspend () -> String,
    ): Boolean {
        updateCourseStep(index, CourseStepStatus.RUNNING, runningDetail)
        return runCatching { block() }
            .onSuccess { detail -> updateCourseStep(index, CourseStepStatus.SUCCESS, detail) }
            .onFailure { error ->
                updateCourseStep(
                    index,
                    CourseStepStatus.ERROR,
                    error.message ?: error.javaClass.simpleName,
                )
            }
            .isSuccess
    }

    private suspend fun finishCourseDemo(evidence: List<JSONObject>) {
        val now = System.currentTimeMillis()
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("type", "course-demo")
            .put("createdAt", now)
            .put("stages", JSONArray(evidence))
            .toString(2)
        val file = runCatching {
            reportWriter.writeInternalReport("course-demo-$now.json", report)
        }.getOrNull()
        _state.update { current ->
            current.copy(
                courseDemo = current.courseDemo.copy(
                    running = false,
                    reportPath = file?.absolutePath,
                    finishedAt = now,
                ),
                message = if (file != null) "课程链路已完成，验证报告已写入内部文件" else null,
                error = if (file == null) "课程链路结束，但验证报告写入失败" else null,
            )
        }
    }

    private fun updateCourseStep(index: Int, status: CourseStepStatus, detail: String) {
        _state.update { current ->
            val steps = current.courseDemo.steps.mapIndexed { stepIndex, step ->
                if (stepIndex == index) step.copy(status = status, detail = detail) else step
            }
            current.copy(courseDemo = current.courseDemo.copy(steps = steps))
        }
    }

    private fun countRows(uri: Uri): Int =
        application.contentResolver.query(uri, null, null, null, null)?.use { it.count } ?: 0

    private fun httpStatus(port: Int, path: String, token: String?): Int {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 3_000
            if (token != null) setRequestProperty("X-RootPilot-Token", token)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes() }
            status
        } finally {
            connection.disconnect()
        }
    }

    fun consumeMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                DashboardViewModel(
                    application = container.application,
                    deviceRepository = container.deviceRepository,
                    rootShell = container.rootShell,
                    agentServer = container.agentServer,
                    settingsStore = container.settingsStore,
                    historyRepository = container.historyRepository,
                    tokenStore = container.tokenStore,
                    reportWriter = container.reportWriter,
                )
            }
        }
    }
}
