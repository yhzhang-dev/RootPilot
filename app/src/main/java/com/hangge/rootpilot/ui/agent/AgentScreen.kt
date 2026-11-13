package com.hangge.rootpilot.ui.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.domain.model.ApiRequestLog
import com.hangge.rootpilot.ui.components.ConfirmDialog
import com.hangge.rootpilot.ui.components.EmptyState
import com.hangge.rootpilot.ui.components.InlineMessage
import com.hangge.rootpilot.ui.components.KeyValueRow
import com.hangge.rootpilot.ui.components.MonoLabel
import com.hangge.rootpilot.ui.components.PillTone
import com.hangge.rootpilot.ui.components.SectionCard
import com.hangge.rootpilot.ui.components.StatusPill
import com.hangge.rootpilot.ui.components.ToggleRow
import com.hangge.rootpilot.ui.components.formatTime

private val endpoints = listOf(
    "GET" to "/v1/health（免 Token）",
    "GET" to "/v1/status",
    "GET" to "/v1/capabilities",
    "GET" to "/v1/network/status",
    "GET" to "/v1/sensors",
    "GET" to "/v1/sensors/sample?type=1",
    "GET" to "/v1/location/current",
    "GET" to "/v1/screen/screenshot",
    "GET" to "/v1/ui/tree",
    "GET" to "/v1/files?path=",
    "GET" to "/v1/files/read?path=",
    "GET" to "/v1/notifications",
    "GET" to "/v1/events?after=0&timeoutMs=25000",
    "GET" to "/v1/subscriptions",
    "GET" to "/v1/automation/observe",
    "GET" to "/v1/ui/foreground",
    "GET" to "/v1/apps?query=&limit=50",
    "GET" to "/v1/history?limit=50",
    "POST" to "/v1/apps/{pkg}/launch",
    "POST" to "/v1/apps/{pkg}/freeze",
    "POST" to "/v1/apps/{pkg}/unfreeze",
    "POST" to "/v1/apps/{pkg}/force-stop",
    "POST" to "/v1/ui/tap",
    "POST" to "/v1/ui/swipe",
    "POST" to "/v1/ui/key",
    "POST" to "/v1/ui/text",
    "POST" to "/v1/files/write",
    "POST" to "/v1/notifications/dismiss",
    "POST" to "/v1/subscriptions/location/start|stop",
    "POST" to "/v1/subscriptions/sensor/start|stop",
    "POST" to "/v1/automation/act",
)

@Composable
fun AgentScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: AgentViewModel = viewModel(
        factory = AgentViewModel.factory(container, context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRegenerateDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startService() }

    fun requestStart() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startService()
        }
    }

    if (showRegenerateDialog) {
        ConfirmDialog(
            title = "重新生成 Token？",
            message = "旧 Token 会立即失效，桌面 Agent 需要使用新 Token。",
            confirmLabel = "重新生成",
            destructive = true,
            onConfirm = viewModel::regenerateToken,
            onDismiss = { showRegenerateDialog = false },
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
        if (state.serverError != null) {
            item { InlineMessage(text = state.serverError!!, isError = true) }
        }

        item {
            SectionCard(
                title = "Agent 服务",
                subtitle = "前台服务，仅监听 127.0.0.1",
                trailing = {
                    StatusPill(
                        text = if (state.running) "运行中" else "未启动",
                        tone = if (state.running) PillTone.OK else PillTone.NEUTRAL,
                    )
                },
            ) {
                ToggleRow(
                    title = "启用服务",
                    subtitle = "启动后显示常驻通知",
                    checked = state.running,
                    onCheckedChange = { enabled -> if (enabled) requestStart() else viewModel.stopService() },
                )
                KeyValueRow("监听地址", "127.0.0.1:${state.settings.agentPort}")
                if (state.running && state.boundPort != 0) {
                    KeyValueRow("实际端口", state.boundPort.toString())
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.portInput,
                        onValueChange = viewModel::savePort,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.portError != null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = viewModel::commitPort) { Text("保存") }
                }
                if (state.portError != null) {
                    Text(
                        text = state.portError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "桌面 Agent 通过 adb forward tcp:8765 tcp:8765 访问；服务不会绑定公网地址。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "访问 Token", subtitle = "AES/GCM 加密保存在 SharedPreferences") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.maskedToken.ifBlank { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val token = viewModel.copyToken()
                            if (token != null) {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("RootPilot Agent Token", token))
                            }
                        },
                    ) {
                        Text("复制")
                    }
                    TextButton(onClick = { showRegenerateDialog = true }) { Text("重新生成") }
                }
                Text(
                    text = "Token 不会写入日志、数据库或导出文件；请求头使用 X-RootPilot-Token。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "接口", subtitle = "HTTP/1.1 · JSON · 每个连接一个请求") {
                endpoints.forEach { (method, path) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoLabel(method)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "本地自检", subtitle = "HttpURLConnection 访问回环地址") {
                OutlinedButton(
                    onClick = viewModel::selfTest,
                    enabled = !state.testing,
                ) {
                    Text(if (state.testing) "测试中…" else "测试本地 API")
                }
                val selfTest = state.selfTest
                if (selfTest != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    selfTest.probes.forEach { probe -> SelfTestRow(probe) }
                    Text(
                        text = "完成于 ${formatTime(selfTest.finishedAt, full = true)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Provider 自检", subtitle = "通过 ContentResolver 查询只读 Provider") {
                OutlinedButton(
                    onClick = viewModel::providerSelfTest,
                    enabled = !state.checkingProvider,
                ) {
                    Text(if (state.checkingProvider) "查询中…" else "查询 Provider")
                }
                val check = state.providerCheck
                if (check != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (check.error != null) {
                        InlineMessage(text = check.error, isError = true)
                    } else {
                        KeyValueRow("snapshots", "${check.snapshots} 行")
                        KeyValueRow("actions", "${check.actions} 行")
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "请求日志",
                subtitle = "最近 20 条，仅记录方法/路径/状态/耗时",
            ) {
                if (state.requests.isEmpty()) {
                    EmptyState("暂无请求", "启动服务并执行自检后会出现记录")
                } else {
                    state.requests.take(20).forEach { log -> ApiLogRow(log) }
                }
            }
        }
    }
}

@Composable
private fun SelfTestRow(probe: HttpProbe) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(
            text = if (probe.status == 0) "ERR" else probe.status.toString(),
            tone = when {
                probe.status in 200..299 -> PillTone.OK
                probe.status == 401 -> PillTone.WARN
                else -> PillTone.ERROR
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = probe.label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "${probe.path} · ${probe.latencyMs}ms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (probe.body.isNotBlank()) {
                Text(
                    text = probe.body,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun ApiLogRow(log: ApiRequestLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${log.durationMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
