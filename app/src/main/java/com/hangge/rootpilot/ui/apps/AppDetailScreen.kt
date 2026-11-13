package com.hangge.rootpilot.ui.apps

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.domain.model.AppInfo
import com.hangge.rootpilot.domain.model.AppPermission
import com.hangge.rootpilot.ui.components.AppIcon
import com.hangge.rootpilot.ui.components.ConfirmDialog
import com.hangge.rootpilot.ui.components.InlineMessage
import com.hangge.rootpilot.ui.components.KeyValueRow
import com.hangge.rootpilot.ui.components.LoadingRow
import com.hangge.rootpilot.ui.components.PillTone
import com.hangge.rootpilot.ui.components.SectionCard
import com.hangge.rootpilot.ui.components.StatusPill
import com.hangge.rootpilot.ui.components.formatTime

private enum class PendingAction { FREEZE, FORCE_STOP }

@Composable
fun AppDetailScreen(
    container: AppContainer,
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val viewModel: AppDetailViewModel = viewModel(
        factory = AppDetailViewModel.factory(container, packageName),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    val app = state.app
    if (pendingAction != null && app != null) {
        val action = pendingAction!!
        ConfirmDialog(
            title = if (action == PendingAction.FREEZE) "冻结应用？" else "强制停止应用？",
            message = "目标：${app.label}\n包名：${app.packageName}\n\n" +
                if (action == PendingAction.FREEZE) {
                    "冻结后该应用会从桌面与最近任务中消失，可随时解冻。"
                } else {
                    "强制停止会结束该应用的全部进程，正在运行的任务会中断。"
                },
            confirmLabel = if (action == PendingAction.FREEZE) "冻结" else "强制停止",
            destructive = true,
            onConfirm = {
                if (action == PendingAction.FREEZE) viewModel.freeze() else viewModel.forceStop()
            },
            onDismiss = { pendingAction = null },
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
        if (state.loading) {
            item { LoadingRow(text = "读取应用信息…") }
        }

        if (app != null) {
            item { DetailHeader(app, state.rootAvailable, viewModel::loadIcon) }

            item {
                SectionCard(title = "应用信息", subtitle = "PackageManager 只读查询") {
                    KeyValueRow("包名", app.packageName)
                    KeyValueRow("版本", "${app.versionName}（${app.versionCode}）")
                    KeyValueRow("targetSdk", app.targetSdk.toString())
                    KeyValueRow("minSdk", app.minSdk.toString())
                    KeyValueRow("安装时间", installTimeLabel(app.installTime))
                    KeyValueRow("类型", if (app.isSystem) "系统应用" else "用户应用")
                    KeyValueRow("启用状态", if (app.isEnabled) "已启用" else "已停用")
                    KeyValueRow(
                        "Root",
                        when (state.rootAvailable) {
                            null -> "检测中…"
                            true -> "已授权"
                            false -> "未授权 Root"
                        },
                    )
                }
            }

            item {
                SectionCard(title = "Root 操作", subtitle = "全部经过 ActionExecutor 并写入审计记录") {
                    ActionBlock(state, viewModel, onFreeze = { pendingAction = PendingAction.FREEZE }, onForceStop = { pendingAction = PendingAction.FORCE_STOP })
                }
            }

            item {
                PermissionsHeader(app.permissions)
            }
            val permissions = app.permissions.sortedWith(
                compareByDescending<AppPermission> { it.dangerous }.thenBy { it.name },
            )
            items(permissions, key = { it.name }) { permission ->
                PermissionRow(permission)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun DetailHeader(
    app: AppInfo,
    rootAvailable: Boolean?,
    loader: suspend (String) -> android.graphics.Bitmap?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(packageName = app.packageName, label = app.label, loader = loader, size = 56.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (app.isSystem) StatusPill("系统应用", PillTone.NEUTRAL)
                else StatusPill("用户应用", PillTone.INFO)
                if (!app.isEnabled) StatusPill("已停用", PillTone.ERROR)
                when (rootAvailable) {
                    true -> StatusPill("ROOT", PillTone.OK)
                    false -> StatusPill("未授权", PillTone.ERROR)
                    null -> StatusPill("检测中", PillTone.WARN)
                }
            }
        }
    }
}

@Composable
private fun ActionBlock(
    state: AppDetailUiState,
    viewModel: AppDetailViewModel,
    onFreeze: () -> Unit,
    onForceStop: () -> Unit,
) {
    val app = state.app ?: return
    val root = state.rootAvailable
    val systemBlocked = app.isSystem
    val protectedBlocked = state.protectedReason != null
    val safeBlocked = state.safeMode
    val canOperate = root == true && !state.busy
    val canFreeze = canOperate && !systemBlocked && !protectedBlocked && !safeBlocked && app.isEnabled
    val canUnfreeze = canOperate && app.isEnabled.not()
    val canForceStop = canOperate && !systemBlocked && !protectedBlocked && !safeBlocked

    val reason = when {
        root == false -> "未授权 Root，冻结 / 解冻 / 强制停止均不可用"
        root == null -> "正在检测 Root…"
        systemBlocked -> "该应用是系统应用，不支持冻结或强制停止"
        protectedBlocked -> "已保护（${state.protectedReason}），禁止冻结或强制停止"
        safeBlocked -> "SafeMode 已开启：冻结与强制停止不可用，解冻仍可用"
        else -> null
    }
    if (reason != null) {
        InlineMessage(text = reason, isError = root == false || safeBlocked)
        Spacer(modifier = Modifier.height(10.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onFreeze, enabled = canFreeze) { Text("冻结") }
        OutlinedButton(onClick = viewModel::unfreeze, enabled = canUnfreeze) { Text("解冻") }
        Button(
            onClick = onForceStop,
            enabled = canForceStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text("强制停止")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = if (state.busy) "正在执行…" else "冻结与强制停止会先校验 SafeMode、系统应用与受保护应用",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionsHeader(permissions: List<AppPermission>) {
    val dangerous = permissions.count { it.dangerous }
    SectionCard(
        title = "权限",
        subtitle = "共 ${permissions.size} 项 · 危险权限 $dangerous 项",
    ) {
        if (permissions.isEmpty()) {
            Text(
                text = "该应用未声明运行时权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(permission: AppPermission) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = permission.name.substringAfterLast('.'),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (permission.dangerous) {
                StatusPill("危险", PillTone.ERROR)
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (permission.granted) {
                StatusPill("已授予", PillTone.OK)
            } else {
                StatusPill("未授予", PillTone.NEUTRAL)
            }
        }
        Text(
            text = permission.name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** System packages sometimes report a 1970s epoch install time; label those as preinstalled. */
private fun installTimeLabel(installTime: Long): String =
    if (installTime < MIN_PLAUSIBLE_INSTALL_TIME) "系统预装" else formatTime(installTime, full = true)

private const val MIN_PLAUSIBLE_INSTALL_TIME = 946_684_800_000L
