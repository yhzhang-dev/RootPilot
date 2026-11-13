package com.hangge.rootpilot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.domain.model.AppTheme
import com.hangge.rootpilot.ui.components.ConfirmDialog
import com.hangge.rootpilot.ui.components.InlineMessage
import com.hangge.rootpilot.ui.components.KeyValueRow
import com.hangge.rootpilot.ui.components.PillTone
import com.hangge.rootpilot.ui.components.SectionCard
import com.hangge.rootpilot.ui.components.StatusPill
import com.hangge.rootpilot.ui.components.ToggleRow

@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(container, context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var showUnfreezeDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        ConfirmDialog(
            title = "清空全部历史？",
            message = "将删除快照、操作与 API 请求日志，此操作不可恢复。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = viewModel::clearHistory,
            onDismiss = { showClearDialog = false },
        )
    }
    if (showUnfreezeDialog) {
        ConfirmDialog(
            title = "解冻全部 RootPilot 冻结的应用？",
            message = "将按历史记录逐个解冻由 RootPilot 冻结的用户应用；系统应用与未安装应用会跳过。",
            confirmLabel = "解冻",
            destructive = false,
            onConfirm = viewModel::unfreezeAll,
            onDismiss = { showUnfreezeDialog = false },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.message != null) {
            item { InlineMessage(text = state.message!!, isError = false) }
        }
        if (state.error != null) {
            item { InlineMessage(text = state.error!!, isError = true) }
        }

        item {
            SectionCard(
                title = "Root 状态",
                subtitle = "Runtime 事实，不影响应用启动",
                trailing = {
                    when (state.rootAvailable) {
                        true -> StatusPill("已授权", PillTone.OK)
                        false -> StatusPill("未授权", PillTone.ERROR)
                        null -> StatusPill("未检测", PillTone.NEUTRAL)
                    }
                },
            ) {
                Text(
                    text = "无 Root 时 RootPilot 退化为只读设备与应用检查器；所有特权按钮会显示禁用原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::checkRoot,
                    enabled = !state.checkingRoot,
                ) {
                    Text(if (state.checkingRoot) "检测中…" else "重新检测 Root")
                }
            }
        }

        item {
            SectionCard(title = "安全策略", subtitle = "默认最严格，改动即时生效") {
                ToggleRow(
                    title = "SafeMode",
                    subtitle = "开启时冻结与强制停止在 UI 和 API 中均被拒绝；解冻始终可用",
                    checked = state.settings.safeMode,
                    onCheckedChange = viewModel::setSafeMode,
                )
                ToggleRow(
                    title = "允许 Agent 控制",
                    subtitle = "写接口还需要 Token 与 SafeMode 关闭；读接口不受影响",
                    checked = state.settings.agentControl,
                    onCheckedChange = viewModel::setAgentControl,
                )
            }
        }

        item {
            SectionCard(title = "外观", subtitle = "Material 3 · 深色工具风格") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = state.settings.theme == theme,
                            onClick = { viewModel.setTheme(theme) },
                            label = {
                                Text(
                                    when (theme) {
                                        AppTheme.SYSTEM -> "跟随系统"
                                        AppTheme.LIGHT -> "浅色"
                                        AppTheme.DARK -> "深色"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "数据",
                subtitle = "快照 ${state.counts.snapshots} · 操作 ${state.counts.actions} · API ${state.counts.apiRequests}",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { showUnfreezeDialog = true },
                        enabled = !state.busy,
                    ) {
                        Text("解冻全部")
                    }
                    TextButton(
                        onClick = { showClearDialog = true },
                        enabled = !state.busy,
                    ) {
                        Text("清空历史", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    text = "解冻全部只针对 RootPilot 自己冻结过的应用，不会影响系统应用或其他工具的操作。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "关于", subtitle = "本地优先 · 无云 · 无遥测") {
                KeyValueRow("应用", "RootPilot")
                KeyValueRow("版本", state.versionName)
                KeyValueRow("包名", "com.hangge.rootpilot")
                KeyValueRow("Agent 监听", "127.0.0.1 回环")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Android 课程作业项目：实践文件存储、SharedPreferences、Room/SQLite、ContentProvider、前台 Service、广播与本地网络编程。没有云服务、分析 SDK 或外部 API。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "已知边界", subtitle = "设计取舍") {
                Text(
                    text = "• 不提供任意 shell 命令，只有固定模板；\n" +
                        "• Provider 只读且不导出，外部集成走 HTTP API；\n" +
                        "• 桌面端访问需 adb forward；\n" +
                        "• 冻结桌面/输入法被保护规则阻止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
