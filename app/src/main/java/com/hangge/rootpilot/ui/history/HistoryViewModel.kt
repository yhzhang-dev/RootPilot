package com.hangge.rootpilot.ui.history

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.data.export.ExportFormatter
import com.hangge.rootpilot.data.export.ReportWriter
import com.hangge.rootpilot.data.repository.DeviceRepository
import com.hangge.rootpilot.data.repository.HistoryCounts
import com.hangge.rootpilot.data.repository.HistoryRepository
import com.hangge.rootpilot.data.repository.HistoryType
import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HistoryTab(val label: String, val historyType: HistoryType) {
    SNAPSHOTS("快照", HistoryType.SNAPSHOT),
    ACTIONS("操作", HistoryType.ACTION),
    API("API 请求", HistoryType.API),
}

data class HistoryUiState(
    val loading: Boolean = true,
    val tab: HistoryTab = HistoryTab.ACTIONS,
    val snapshots: List<DeviceSnapshot> = emptyList(),
    val actions: List<ActionRecord> = emptyList(),
    val apiRequests: List<ApiRequestLog> = emptyList(),
    val counts: HistoryCounts = HistoryCounts(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class HistoryViewModel(
    private val application: Application,
    private val historyRepository: HistoryRepository,
    private val deviceRepository: DeviceRepository,
    private val reportWriter: ReportWriter,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                historyRepository.snapshots,
                historyRepository.actions,
                historyRepository.apiRequests,
            ) { snapshots, actions, apiRequests ->
                Triple(snapshots, actions, apiRequests)
            }.collect { (snapshots, actions, apiRequests) ->
                _state.update {
                    it.copy(
                        loading = false,
                        snapshots = snapshots,
                        actions = actions,
                        apiRequests = apiRequests,
                        counts = HistoryCounts(
                            snapshots = snapshots.size,
                            actions = actions.size,
                            apiRequests = apiRequests.size,
                        ),
                    )
                }
            }
        }
    }

    fun selectTab(tab: HistoryTab) {
        _state.update { it.copy(tab = tab, message = null, error = null) }
    }

    fun exportJson(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            runCatching {
                val device = deviceRepository.profile()
                val snapshots = historyRepository.allSnapshots()
                val actions = historyRepository.allActions()
                val content = ExportFormatter.toJson(
                    exportedAt = System.currentTimeMillis(),
                    device = device,
                    snapshots = snapshots,
                    actions = actions,
                )
                writeInternalAndSaf(uri, content, "report-${System.currentTimeMillis()}.json")
                "已导出 JSON：快照 ${snapshots.size} 条，操作 ${actions.size} 条"
            }.onSuccess { message ->
                _state.update { it.copy(busy = false, message = message) }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, error = "导出失败：${error.message}") }
            }
        }
    }

    fun exportCsv(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            val tab = _state.value.tab
            runCatching {
                val timestamp = System.currentTimeMillis()
                val (content, fileName) = when (tab) {
                    HistoryTab.SNAPSHOTS -> ExportFormatter.snapshotsToCsv(historyRepository.allSnapshots()) to
                        "report-$timestamp-snapshots.csv"
                    HistoryTab.ACTIONS -> ExportFormatter.actionsToCsv(historyRepository.allActions()) to
                        "report-$timestamp-actions.csv"
                    HistoryTab.API -> ExportFormatter.apiToCsv(historyRepository.allApiRequests()) to
                        "report-$timestamp-api.csv"
                }
                writeInternalAndSaf(uri, content, fileName)
                "已导出 ${tab.label} CSV"
            }.onSuccess { message ->
                _state.update { it.copy(busy = false, message = message) }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, error = "导出失败：${error.message}") }
            }
        }
    }

    fun clearCurrent() {
        if (_state.value.busy) return
        val tab = _state.value.tab
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            runCatching { historyRepository.clear(tab.historyType) }
                .onSuccess {
                    _state.update { it.copy(busy = false, message = "已清空 ${tab.label} 记录") }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = "清空失败：${error.message}") }
                }
        }
    }

    fun consumeMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    private suspend fun writeInternalAndSaf(uri: Uri, content: String, fileName: String) {
        reportWriter.writeInternalReport(fileName, content)
        withContext(Dispatchers.IO) {
            val stream = application.contentResolver.openOutputStream(uri)
                ?: error("无法写入所选文件")
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
    }

    companion object {
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer {
                HistoryViewModel(
                    application = context.applicationContext as Application,
                    historyRepository = container.historyRepository,
                    deviceRepository = container.deviceRepository,
                    reportWriter = container.reportWriter,
                )
            }
        }
    }
}
