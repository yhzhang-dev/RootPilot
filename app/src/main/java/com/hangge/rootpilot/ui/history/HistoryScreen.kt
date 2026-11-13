package com.hangge.rootpilot.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import com.hangge.rootpilot.ui.components.ConfirmDialog
import com.hangge.rootpilot.ui.components.EmptyState
import com.hangge.rootpilot.ui.components.InlineMessage
import com.hangge.rootpilot.ui.components.KeyValueRow
import com.hangge.rootpilot.ui.components.LoadingRow
import com.hangge.rootpilot.ui.components.PillTone
import com.hangge.rootpilot.ui.components.StatusPill
import com.hangge.rootpilot.ui.components.actionLabel
import com.hangge.rootpilot.ui.components.formatBytes
import com.hangge.rootpilot.ui.components.formatTime
import com.hangge.rootpilot.ui.components.shortPackage

@Composable
fun HistoryScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(container, context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJson) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsv) }

    if (showClearDialog) {
        ConfirmDialog(
            title = "清空${state.tab.label}记录？",
            message = "将删除当前分类（${state.tab.label}）的全部本地记录，此操作不可恢复。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = viewModel::clearCurrent,
            onDismiss = { showClearDialog = false },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }

        item {
            Text(
                text = "快照 ${state.counts.snapshots} · 操作 ${state.counts.actions} · API ${state.counts.apiRequests}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        jsonLauncher.launch("rootpilot-${System.currentTimeMillis()}.json")
                    },
                    enabled = !state.busy,
                ) {
                    Text("导出 JSON")
                }
                TextButton(
                    onClick = {
                        csvLauncher.launch("rootpilot-${System.currentTimeMillis()}.csv")
                    },
                    enabled = !state.busy,
                ) {
                    Text("导出 CSV")
                }
                TextButton(
                    onClick = { showClearDialog = true },
                    enabled = !state.busy,
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (state.message != null) {
            item { InlineMessage(text = state.message!!, isError = false) }
        }
        if (state.error != null) {
            item { InlineMessage(text = state.error!!, isError = true) }
        }

        if (state.loading) {
            item { LoadingRow(text = "读取历史记录…") }
        } else {
            when (state.tab) {
                HistoryTab.SNAPSHOTS -> {
                    if (state.snapshots.isEmpty()) {
                        item { EmptyState("暂无快照", "在总览页点击“采集快照”后会出现记录") }
                    }
                    items(state.snapshots, key = { "snapshot-${it.id}" }) { snapshot ->
                        SnapshotItem(snapshot)
                    }
                }

                HistoryTab.ACTIONS -> {
                    if (state.actions.isEmpty()) {
                        item { EmptyState("暂无操作", "冻结、解冻、强制停止与快照都会留下审计记录") }
                    }
                    items(state.actions, key = { "action-${it.id}" }) { record ->
                        ActionItem(record)
                    }
                }

                HistoryTab.API -> {
                    if (state.apiRequests.isEmpty()) {
                        item { EmptyState("暂无 API 请求", "启动 Agent 服务并执行自检后会出现请求日志") }
                    }
                    items(state.apiRequests, key = { "api-${it.id}" }) { log ->
                        ApiItem(log)
                    }
                }
            }
        }

        item {
            Text(
                text = "导出会同时写入应用内部 files/reports 副本；Token 不会出现在任何导出文件中。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HistoryCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun SnapshotItem(snapshot: DeviceSnapshot) {
    HistoryCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = if (snapshot.rootAvailable) "ROOT" else "无 ROOT",
                tone = if (snapshot.rootAvailable) PillTone.OK else PillTone.NEUTRAL,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTime(snapshot.capturedAt, full = true),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        KeyValueRow("电量", snapshot.batteryPercent?.let { "$it%" } ?: "—")
        KeyValueRow(
            "充电",
            when (snapshot.charging) {
                true -> "充电中"
                false -> "未充电"
                null -> "未知"
            },
        )
        KeyValueRow("可用内存", formatBytes(snapshot.memoryFreeBytes))
        KeyValueRow("可用存储", formatBytes(snapshot.storageFreeBytes))
        KeyValueRow("应用数", snapshot.appCount.toString())
    }
}

@Composable
private fun ActionItem(record: ActionRecord) {
    HistoryCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = if (record.success) "成功" else "失败",
                tone = if (record.success) PillTone.OK else PillTone.ERROR,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionLabel(record.action),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(record.createdAt, full = true),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (record.packageName != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = shortPackage(record.packageName, 44),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = record.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ApiItem(log: ApiRequestLog) {
    HistoryCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = log.statusCode.toString(),
                tone = when {
                    log.statusCode in 200..299 -> PillTone.OK
                    log.statusCode == 401 || log.statusCode == 403 -> PillTone.WARN
                    else -> PillTone.ERROR
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${log.method} ${log.path}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${log.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatTime(log.createdAt, full = true),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
