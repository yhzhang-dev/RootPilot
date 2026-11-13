package com.hangge.rootpilot.ui.dashboard

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.domain.model.DeviceSnapshot
import com.hangge.rootpilot.ui.components.InlineMessage
import com.hangge.rootpilot.ui.components.KeyValueRow
import com.hangge.rootpilot.ui.components.LoadingRow
import com.hangge.rootpilot.ui.components.PillTone
import com.hangge.rootpilot.ui.components.SectionCard
import com.hangge.rootpilot.ui.components.StatusPill
import com.hangge.rootpilot.ui.components.actionLabel
import com.hangge.rootpilot.ui.components.formatBytes
import com.hangge.rootpilot.ui.components.formatTime

@Composable
fun DashboardScreen(
    container: AppContainer,
    onOpenAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本地设备控制台",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "只读即可用 · 特权操作需 Root",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RootPill(state.rootAvailable)
            }
        }

        if (state.message != null) {
            item { InlineMessage(text = state.message!!, isError = false) }
        }
        if (state.error != null) {
            item { InlineMessage(text = state.error!!, isError = true) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::refresh,
                    enabled = !state.refreshing,
                ) {
                    Text(if (state.refreshing) "检测中…" else "刷新")
                }
                Button(
                    onClick = viewModel::captureSnapshot,
                    enabled = !state.capturing && !state.loading,
                ) {
                    Text(if (state.capturing) "采集中…" else "采集快照")
                }
            }
        }

        if (state.loading) {
            item { LoadingRow(text = "正在读取设备信息…") }
        }

        item {
            CourseDemoCard(
                demo = state.courseDemo,
                onRun = viewModel::runCourseDemo,
            )
        }

        item {
            SectionCard(title = "设备", subtitle = "Build 信息与已安装应用数量") {
                KeyValueRow("型号", state.profile?.model ?: "—")
                KeyValueRow("制造商", state.profile?.manufacturer ?: "—")
                KeyValueRow(
                    "Android",
                    state.profile?.let { "${it.androidRelease}（SDK ${it.sdkInt}）" } ?: "—",
                )
                KeyValueRow("已安装应用", state.profile?.appCount?.toString() ?: "—")
                KeyValueRow(
                    "Root",
                    when (state.rootAvailable) {
                        null -> "检测中…"
                        true -> "已授权"
                        false -> "未授权 Root"
                    },
                    valueColor = when (state.rootAvailable) {
                        true -> MaterialTheme.colorScheme.secondary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        item {
            SectionCard(title = "电池", subtitle = "动态广播实时更新") {
                KeyValueRow(
                    "电量",
                    state.battery.percent?.let { "$it%" } ?: "—",
                )
                if (state.battery.percent != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (state.battery.percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                KeyValueRow(
                    "充电状态",
                    when (state.battery.charging) {
                        true -> "充电中"
                        false -> "未充电"
                        null -> "未知"
                    },
                )
                KeyValueRow(
                    "温度",
                    state.battery.temperatureC?.let { String.format(java.util.Locale.ROOT, "%.1f °C", it) } ?: "—",
                )
            }
        }

        item {
            SectionCard(title = "内存与存储", subtitle = "最近一次快照") {
                val snapshot = state.snapshot
                KeyValueRow(
                    "可用内存",
                    if (snapshot == null) "尚未采集" else formatBytes(snapshot.memoryFreeBytes),
                )
                KeyValueRow(
                    "内存总量",
                    state.profile?.let { formatBytes(it.memoryTotalBytes) } ?: "—",
                )
                KeyValueRow(
                    "可用存储",
                    if (snapshot == null) "尚未采集" else formatBytes(snapshot.storageFreeBytes),
                )
                KeyValueRow(
                    "存储总量",
                    state.profile?.let { formatBytes(it.storageTotalBytes) } ?: "—",
                )
                KeyValueRow(
                    "快照时间",
                    snapshot?.let { formatTime(it.capturedAt, full = true) } ?: "—",
                )
            }
        }

        item {
            SectionCard(
                title = "Agent 服务",
                subtitle = "仅监听 127.0.0.1，需要 Token",
                trailing = {
                    StatusPill(
                        text = if (state.agentRunning) "运行中" else "未启动",
                        tone = if (state.agentRunning) PillTone.OK else PillTone.NEUTRAL,
                    )
                },
            ) {
                KeyValueRow("监听端口", state.agentPort.toString())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onOpenAgent) { Text("打开 Agent") }
                }
            }
        }

        item {
            SectionCard(title = "最近操作", subtitle = "来自本机历史记录") {
                val last = state.lastAction
                if (last == null) {
                    Text(
                        text = "暂无记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    KeyValueRow("类型", actionLabel(last.action))
                    KeyValueRow("对象", last.packageName ?: "—")
                    KeyValueRow("结果", if (last.success) "成功" else "失败")
                    KeyValueRow("时间", formatTime(last.createdAt, full = true))
                    Text(
                        text = last.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item {
            SnapshotFootnote(state.snapshot)
        }
    }
}

@Composable
private fun CourseDemoCard(
    demo: CourseDemoState,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "课程主线演示",
        subtitle = "一次运行串联存储、共享、后台组件与本地网络",
        trailing = {
            StatusPill(
                text = when {
                    demo.running -> "运行中"
                    demo.finishedAt != null && demo.steps.all { it.status == CourseStepStatus.SUCCESS } -> "已通过"
                    demo.finishedAt != null -> "有异常"
                    else -> "未运行"
                },
                tone = when {
                    demo.running -> PillTone.INFO
                    demo.finishedAt != null && demo.steps.all { it.status == CourseStepStatus.SUCCESS } -> PillTone.OK
                    demo.finishedAt != null -> PillTone.ERROR
                    else -> PillTone.NEUTRAL
                },
            )
        },
    ) {
        demo.steps.forEachIndexed { index, step ->
            CourseStepRow(number = index + 1, step = step)
            if (index != demo.steps.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 44.dp, top = 8.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onRun,
            enabled = !demo.running,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (demo.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (demo.running) "正在验证课程链路…" else "运行完整链路")
        }
        if (demo.reportPath != null) {
            Text(
                text = "验证报告：${demo.reportPath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun CourseStepRow(number: Int, step: CourseDemoStep) {
    val colors = MaterialTheme.colorScheme
    val (container, content, statusText) = when (step.status) {
        CourseStepStatus.IDLE -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, "等待")
        CourseStepStatus.RUNNING -> Triple(colors.primaryContainer, colors.onPrimaryContainer, "进行中")
        CourseStepStatus.SUCCESS -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, "完成")
        CourseStepStatus.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, "失败")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "第 $number 步，${step.title}，$statusText，${step.detail}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = container,
            contentColor = content,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(number.toString(), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
            }
            Text(
                text = step.chapter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RootPill(rootAvailable: Boolean?) {
    when (rootAvailable) {
        null -> StatusPill(text = "检测中", tone = PillTone.WARN)
        true -> StatusPill(text = "ROOT", tone = PillTone.OK)
        false -> StatusPill(text = "未授权", tone = PillTone.ERROR)
    }
}

@Composable
private fun SnapshotFootnote(snapshot: DeviceSnapshot?) {
    if (snapshot != null) {
        Text(
            text = "快照已写入 Room · 可在 History 查看或导出",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
