package com.hangge.rootpilot.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.repository.HistoryCounts
import com.hangge.rootpilot.data.repository.HistoryRepository
import com.hangge.rootpilot.data.repository.HistoryType
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.domain.ActionExecutor
import com.hangge.rootpilot.domain.model.AppSettings
import com.hangge.rootpilot.domain.model.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val rootAvailable: Boolean? = null,
    val checkingRoot: Boolean = false,
    val busy: Boolean = false,
    val counts: HistoryCounts = HistoryCounts(),
    val versionName: String = "1.0",
    val message: String? = null,
    val error: String? = null,
)

class SettingsViewModel(
    private val application: Application,
    private val settingsStore: SettingsStore,
    private val rootShell: RootShell,
    private val actionExecutor: ActionExecutor,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            combine(
                historyRepository.snapshots,
                historyRepository.actions,
                historyRepository.apiRequests,
            ) { snapshots, actions, requests ->
                HistoryCounts(snapshots.size, actions.size, requests.size)
            }.collect { counts ->
                _state.update { it.copy(counts = counts) }
            }
        }
        viewModelScope.launch {
            val version = runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName
            }.getOrNull() ?: "1.0"
            _state.update { it.copy(versionName = version) }
        }
        checkRoot()
    }

    fun checkRoot() {
        if (_state.value.checkingRoot) return
        viewModelScope.launch {
            _state.update { it.copy(checkingRoot = true, error = null) }
            val root = runCatching { rootShell.refreshRoot() }.getOrDefault(false)
            _state.update { it.copy(checkingRoot = false, rootAvailable = root) }
        }
    }

    fun setSafeMode(enabled: Boolean) = settingsStore.setSafeMode(enabled)

    fun setAgentControl(enabled: Boolean) = settingsStore.setAgentControl(enabled)

    fun setTheme(theme: AppTheme) = settingsStore.setTheme(theme)

    fun unfreezeAll() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, error = null) }
            val root = runCatching { rootShell.isRootAvailable() }.getOrDefault(false)
            if (!root) {
                _state.update { it.copy(busy = false, error = "未授权 Root，无法解冻") }
                return@launch
            }
            val result = runCatching { actionExecutor.unfreezeAllRootPilotFrozen() }.getOrNull()
            _state.update {
                if (result == null) {
                    it.copy(busy = false, error = "解冻失败")
                } else {
                    it.copy(
                        busy = false,
                        message = "已解冻 ${result.succeeded}/${result.attempted} 个 RootPilot 冻结的应用",
                    )
                }
            }
        }
    }

    fun clearHistory() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, error = null) }
            runCatching { historyRepository.clear(HistoryType.ALL) }
                .onSuccess {
                    _state.update { it.copy(busy = false, message = "已清空全部历史记录") }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = "清空失败：${error.message}") }
                }
        }
    }

    fun consumeMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    companion object {
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    application = context.applicationContext as Application,
                    settingsStore = container.settingsStore,
                    rootShell = container.rootShell,
                    actionExecutor = container.actionExecutor,
                    historyRepository = container.historyRepository,
                )
            }
        }
    }
}
