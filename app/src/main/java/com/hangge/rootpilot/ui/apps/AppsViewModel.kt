package com.hangge.rootpilot.ui.apps

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.data.repository.AppRepository
import com.hangge.rootpilot.domain.model.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppFilter(val label: String) {
    ALL("全部"),
    USER("用户"),
    SYSTEM("系统"),
    DISABLED("已停用"),
}

data class AppsUiState(
    val loading: Boolean = true,
    val apps: List<AppInfo> = emptyList(),
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val error: String? = null,
) {
    val visible: List<AppInfo>
        get() {
            val needle = query.trim()
            return apps.filter { app ->
                val matchesQuery = needle.isEmpty() ||
                    app.label.contains(needle, ignoreCase = true) ||
                    app.packageName.contains(needle, ignoreCase = true)
                val matchesFilter = when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.USER -> !app.isSystem
                    AppFilter.SYSTEM -> app.isSystem
                    AppFilter.DISABLED -> !app.isEnabled
                }
                matchesQuery && matchesFilter
            }
        }
}

class AppsViewModel(private val appRepository: AppRepository) : ViewModel() {

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { appRepository.loadApps() }
                .onSuccess { apps ->
                    _state.update { it.copy(loading = false, apps = apps) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = "读取应用列表失败：${error.message}")
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun onFilterChange(filter: AppFilter) {
        _state.update { it.copy(filter = filter) }
    }

    suspend fun loadIcon(packageName: String): Bitmap? = appRepository.loadIcon(packageName)

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AppsViewModel(container.appRepository) }
        }
    }
}
