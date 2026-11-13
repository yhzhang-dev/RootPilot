package com.hangge.rootpilot.ui.apps

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.repository.AppRepository
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.data.system.AppProtection
import com.hangge.rootpilot.domain.ActionErrorCode
import com.hangge.rootpilot.domain.ActionExecutor
import com.hangge.rootpilot.domain.ActionResult
import com.hangge.rootpilot.domain.model.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppDetailUiState(
    val loading: Boolean = true,
    val app: AppInfo? = null,
    val rootAvailable: Boolean? = null,
    val rootChecking: Boolean = false,
    val safeMode: Boolean = true,
    val protectedReason: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class AppDetailViewModel(
    private val packageName: String,
    private val appRepository: AppRepository,
    private val rootShell: RootShell,
    private val actionExecutor: ActionExecutor,
    private val protection: AppProtection,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AppDetailUiState())
    val state: StateFlow<AppDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(safeMode = settings.safeMode) }
            }
        }
        load()
        checkRoot()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val app = runCatching { appRepository.loadApp(packageName) }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    app = app,
                    error = if (app == null) "未找到该应用：$packageName" else null,
                    protectedReason = protection.protectedReason(packageName),
                    safeMode = settingsStore.current().safeMode,
                )
            }
        }
    }

    fun checkRoot(force: Boolean = false) {
        if (_state.value.rootChecking) return
        viewModelScope.launch {
            _state.update { it.copy(rootChecking = true) }
            val root = runCatching { rootShell.isRootAvailable(force) }.getOrDefault(false)
            _state.update {
                it.copy(
                    rootAvailable = root,
                    rootChecking = false,
                    safeMode = settingsStore.current().safeMode,
                )
            }
        }
    }

    fun freeze() = runAction { actionExecutor.freeze(packageName) }

    fun unfreeze() = runAction { actionExecutor.unfreeze(packageName) }

    fun forceStop() = runAction { actionExecutor.forceStop(packageName) }

    suspend fun loadIcon(packageName: String): Bitmap? = appRepository.loadIcon(packageName)

    fun consumeMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun runAction(block: suspend () -> ActionResult) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, error = null) }
            val result = runCatching { block() }.getOrElse { error ->
                ActionResult.failure(
                    ActionErrorCode.COMMAND_FAILED,
                    "执行失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
            val refreshed = runCatching { appRepository.loadApp(packageName) }.getOrNull()
            _state.update {
                it.copy(
                    busy = false,
                    app = refreshed ?: it.app,
                    safeMode = settingsStore.current().safeMode,
                    message = if (result.success) result.message else null,
                    error = if (result.success) null else result.message,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, packageName: String) = viewModelFactory {
            initializer {
                AppDetailViewModel(
                    packageName = packageName,
                    appRepository = container.appRepository,
                    rootShell = container.rootShell,
                    actionExecutor = container.actionExecutor,
                    protection = container.appProtection,
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
}
