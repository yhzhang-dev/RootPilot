package com.hangge.rootpilot.ui.apps

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.domain.model.AppInfo
import com.hangge.rootpilot.ui.components.AppIcon
import com.hangge.rootpilot.ui.components.EmptyState
import com.hangge.rootpilot.ui.components.InlineMessage
import com.hangge.rootpilot.ui.components.LoadingRow
import com.hangge.rootpilot.ui.components.PillTone
import com.hangge.rootpilot.ui.components.StatusPill

@Composable
fun AppsScreen(
    container: AppContainer,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AppsViewModel = viewModel(factory = AppsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索应用名称或包名") },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.onFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "共 ${state.visible.size} 个应用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::load, enabled = !state.loading) {
                    Text("重新读取")
                }
            }
            if (state.error != null) {
                InlineMessage(text = state.error!!, isError = true)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        if (state.loading && state.apps.isEmpty()) {
            LoadingRow(text = "正在读取已安装应用…")
        } else if (state.visible.isEmpty()) {
            EmptyState(
                title = "没有匹配的应用",
                message = if (state.apps.isEmpty()) "读取结果为空，请检查 QUERY_ALL_PACKAGES" else "调整搜索词或筛选条件",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(state.visible, key = { app -> app.packageName }) { app ->
                    AppRow(app = app, onClick = { onOpenApp(app.packageName) }, loader = viewModel::loadIcon)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    onClick: () -> Unit,
    loader: suspend (String) -> Bitmap?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, label = app.label, loader = loader)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isSystem) {
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusPill(text = "系统", tone = PillTone.NEUTRAL)
                }
                if (!app.isEnabled) {
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusPill(text = "已停用", tone = PillTone.ERROR)
                }
            }
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "v${app.versionName} · targetSdk ${app.targetSdk}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
