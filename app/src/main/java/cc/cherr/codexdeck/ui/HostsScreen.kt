package cc.cherr.codexdeck.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState
import cc.cherr.codexdeck.ConnectionPhase
import cc.cherr.codexdeck.HostConnectionState
import cc.cherr.codexdeck.data.AppServerTransportMode
import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.ssh.usesSshTransport

@Composable
fun HostsScreen(
    state: AppState,
    controller: AppController,
    onAdd: () -> Unit,
    onEdit: (HostRecord) -> Unit,
    onDelete: (HostRecord) -> Unit,
    onConnect: (HostRecord) -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val hosts = remember(state.hosts, query) {
        val value = query.trim()
        if (value.isEmpty()) state.hosts
        else state.hosts.filter {
            it.label.contains(value, true) ||
                it.hostname.contains(value, true) ||
                it.username.contains(value, true) ||
                it.groupName.contains(value, true) ||
                it.tags.contains(value, true)
        }
    }
    val connectedCount = state.hosts.count {
        state.connectionState(it.id).connectionPhase == ConnectionPhase.CONNECTED
    }
    Column(Modifier.fillMaxSize()) {
        SectionHeading(
            title = "服务器",
            subtitle = "${state.hosts.size} 台主机 · $connectedCount 已连接 · SSH 凭据由 Android Keystore 保护",
        )
        if (state.activeHostId != null) {
            ConnectionBanner(state, controller::reconnect, controller::disconnect)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("搜索名称、地址、用户名、分组") },
            shape = RoundedCornerShape(18.dp),
        )
        if (hosts.isEmpty()) {
            EmptyPanel(
                title = if (query.isBlank()) "还没有服务器" else "没有匹配结果",
                detail = if (query.isBlank()) {
                    "添加一台运行 Codex 的 Linux 主机。支持密码、SSH Key 和多级 ProxyJump。"
                } else {
                    "试试主机名、用户名、分组或标签。"
                },
                modifier = Modifier.padding(16.dp),
            )
            TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("添加第一台服务器")
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(hosts, key = HostRecord::id) { host ->
                HostCard(
                    host = host,
                    active = state.activeHostId == host.id,
                    connection = state.connectionState(host.id),
                    onConnect = { onConnect(host) },
                    onTerminal = { onOpenTerminal(host.id) },
                    onDisconnect = { controller.disconnect(host.id) },
                    onFavorite = { controller.toggleFavorite(host.id) },
                    onEdit = { onEdit(host) },
                    onDuplicate = { controller.duplicateHost(host.id) },
                    onDelete = { onDelete(host) },
                )
            }
        }
    }
}

@Composable
fun ConnectionBanner(
    state: AppState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val color = when (state.connectionPhase) {
        ConnectionPhase.CONNECTED -> Color(0xFF66D49A)
        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> Color(0xFFFFC857)
        ConnectionPhase.ERROR -> MaterialTheme.colorScheme.error
        ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color, state.connectionMessage)
        Spacer(Modifier.weight(1f))
        if (state.connectionPhase in setOf(ConnectionPhase.ERROR, ConnectionPhase.DISCONNECTED)) {
            TextButton(onClick = onReconnect) { Text("重连") }
        }
        TextButton(onClick = onDisconnect) { Text("断开") }
    }
}

@Composable
private fun HostCard(
    host: HostRecord,
    active: Boolean,
    connection: HostConnectionState,
    onConnect: () -> Unit,
    onTerminal: () -> Unit,
    onDisconnect: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        host.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (host.usesSshTransport()) {
                            "${host.username}@${host.hostname}:${host.port}"
                        } else {
                            host.appServerUrl
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (host.favorite) {
                    Icon(Icons.Default.Star, contentDescription = "已收藏", tint = Color(0xFFFFC857))
                }
                IconButton(onClick = onFavorite) {
                    Text(if (host.favorite) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                }
                BoxWithMenu(
                    expanded = menu,
                    onExpanded = { menu = true },
                    onDismiss = { menu = false },
                    onEdit = onEdit,
                    onDuplicate = onDuplicate,
                    onDelete = onDelete,
                )
            }
            if (host.groupName.isNotBlank() || host.tags.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (host.groupName.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(host.groupName) })
                    }
                    if (host.tags.isNotBlank()) {
                        Text(
                            host.tags,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                when {
                    host.usesSshTransport() -> buildString {
                        append("Codex: ")
                        append(
                            when (host.appServerTransport) {
                                AppServerTransportMode.SSH_EXISTING -> "已有 App Server"
                                AppServerTransportMode.SSH_LAUNCH ->
                                    "启动 App Server · ${host.codexExecutable}"
                                else -> "SSH Tunnel WebSocket"
                            },
                        )
                        if (host.jumpHostId != null) append(" · 经跳板机")
                    }
                    else -> "Codex: Direct WebSocket"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val statusColor = when (connection.connectionPhase) {
                ConnectionPhase.CONNECTED -> Color(0xFF66D49A)
                ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> Color(0xFFFFC857)
                ConnectionPhase.ERROR -> MaterialTheme.colorScheme.error
                ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.outline
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(
                    color = statusColor,
                    label = when (connection.connectionPhase) {
                        ConnectionPhase.CONNECTED -> if (active) "当前服务器" else "已连接"
                        ConnectionPhase.CONNECTING -> "连接中"
                        ConnectionPhase.RECONNECTING -> "重新连接中"
                        ConnectionPhase.ERROR -> "连接失败"
                        ConnectionPhase.DISCONNECTED -> "未连接"
                    },
                )
                if (connection.approvals.isNotEmpty()) {
                    Text(
                        "${connection.approvals.size} 个待审批",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (connection.connectionPhase == ConnectionPhase.ERROR) {
                Text(
                    connection.connectionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val connecting = connection.connectionPhase in setOf(
                    ConnectionPhase.CONNECTING,
                    ConnectionPhase.RECONNECTING,
                )
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    enabled = !connecting,
                ) {
                    Text(
                        when {
                            active && connection.connectionPhase == ConnectionPhase.CONNECTED ->
                                "打开 Codex"
                            connection.connectionPhase == ConnectionPhase.CONNECTED ->
                                "切换并打开"
                            connecting -> "连接中…"
                            connection.connectionPhase == ConnectionPhase.ERROR -> "重试连接"
                            else -> "连接 Codex"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onTerminal,
                    enabled = host.usesSshTransport(),
                ) {
                    Text("Shell")
                }
                if (connection.connectionPhase != ConnectionPhase.DISCONNECTED) {
                    TextButton(onClick = onDisconnect) { Text("断开") }
                }
            }
        }
    }
}

@Composable
private fun BoxWithMenu(
    expanded: Boolean,
    onExpanded: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = onExpanded) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text("编辑") }, onClick = { onDismiss(); onEdit() })
            DropdownMenuItem(text = { Text("复制") }, onClick = { onDismiss(); onDuplicate() })
            DropdownMenuItem(text = { Text("删除") }, onClick = { onDismiss(); onDelete() })
        }
    }
}
