package cc.cherr.codexdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import cc.cherr.codexdeck.ThreadProject
import cc.cherr.codexdeck.groupThreads
import cc.cherr.codexdeck.protocol.CodexThread
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionsScreen(
    state: AppState,
    controller: AppController,
    onNewThread: (String) -> Unit,
    onOpenHosts: () -> Unit,
) {
    val projects = remember(state.visibleThreads) { groupThreads(state.visibleThreads) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(Modifier.fillMaxSize()) {
        SectionHeading(
            title = "Codex 会话",
            subtitle = state.activeHost?.let {
                "${it.label} · ${state.threads.count { thread -> thread.status.isActive }} 个运行中"
            } ?: "连接服务器后读取远端 Thread 列表",
            trailing = {
                if (state.connectionPhase == ConnectionPhase.CONNECTED &&
                    !state.showArchivedThreads
                ) {
                    IconButton(onClick = {
                        onNewThread(projects.firstOrNull()?.path.orEmpty())
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "新建会话")
                    }
                }
            },
        )
        if (state.activeHostId != null) {
            ConnectionBanner(state, controller::reconnect, controller::disconnect)
        }
        when (state.connectionPhase) {
            ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING ->
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            ConnectionPhase.ERROR -> {
                EmptyPanel(
                    title = "连接未建立",
                    detail = state.connectionMessage + "\nAgent 是否继续运行必须回到服务器确认，客户端不会伪造在线状态。",
                    modifier = Modifier.padding(16.dp),
                )
                Button(
                    onClick = { state.activeHostId?.let(controller::connect) ?: onOpenHosts() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(if (state.activeHostId == null) "选择服务器" else "重试连接")
                }
                return@Column
            }
            ConnectionPhase.DISCONNECTED -> {
                EmptyPanel(
                    title = "尚未连接",
                    detail = "从服务器页面选择 Host。CodexDeck 会通过 SSH 启动远端 codex app-server，再读取项目和历史 Thread。",
                    modifier = Modifier.padding(16.dp),
                )
                Button(
                    onClick = onOpenHosts,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("选择服务器") }
                return@Column
            }
            ConnectionPhase.CONNECTED -> Unit
        }
        OutlinedTextField(
            value = state.threadSearch,
            onValueChange = controller::setThreadSearch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("搜索标题、工作目录或预览") },
            shape = RoundedCornerShape(18.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.showArchivedThreads,
                onClick = controller::toggleArchivedThreads,
                label = { Text("归档会话") },
            )
            Text(
                "${state.threads.size} 个已载入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isLoadingThreads && state.threads.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (projects.isEmpty()) {
            if (!state.isLoadingThreads) {
                EmptyPanel(
                    title = when {
                        state.threadSearch.isNotBlank() -> "没有匹配的会话"
                        state.showArchivedThreads -> "没有归档会话"
                        else -> "没有 Codex 会话"
                    },
                    detail = if (state.threadSearch.isBlank()) {
                        "在任意项目下新建 Thread。模型与推理强度从远端 model/list 动态读取。"
                    } else {
                        "清空搜索词或换一个关键词。"
                    },
                    modifier = Modifier.padding(16.dp),
                )
                if (!state.showArchivedThreads) {
                    Button(
                        onClick = { onNewThread("") },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("新建会话") }
                }
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(projects, key = ThreadProject::path) { project ->
                ProjectCard(
                    project = project,
                    collapsed = project.path in collapsed,
                    onToggle = {
                        collapsed = if (project.path in collapsed) {
                            collapsed - project.path
                        } else {
                            collapsed + project.path
                        }
                    },
                    onNewThread = { onNewThread(project.path) },
                    onOpenThread = { controller.openThread(it.id) },
                )
            }
            if (state.threadNextCursor != null || state.isLoadingThreads) {
                item(key = "thread-pagination") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (state.isLoadingThreads) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                        } else {
                            TextButton(onClick = controller::loadMoreThreads) {
                                Text("加载更多会话")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ThreadProject,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onNewThread: () -> Unit,
    onOpenThread: (CodexThread) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        project.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("${project.threads.size}", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onNewThread) {
                    Icon(Icons.Default.Add, contentDescription = "在 ${project.name} 新建会话")
                }
            }
            if (!collapsed) {
                project.threads.forEach { thread ->
                    ThreadRow(thread, onClick = { onOpenThread(thread) })
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: CodexThread, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                thread.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val timestamp = formatTimestamp(thread.updatedAt)
            val branch = thread.gitBranch?.take(80)?.let { "branch $it" }
            val metadata = listOfNotNull(branch, timestamp.takeIf(String::isNotBlank))
                .joinToString(" · ")
            Text(
                thread.preview.ifBlank { metadata },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metadata.isNotBlank() && thread.preview.isNotBlank()) {
                Text(
                    metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (thread.status.isActive) {
            StatusDot(Color(0xFFFFC857), "运行中")
        } else {
            Text(
                formatTimestamp(thread.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NewThreadDialog(
    initialPath: String,
    projects: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Codex Thread") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("远端绝对工作目录")
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    placeholder = { Text("/home/dev/project") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (projects.isNotEmpty()) {
                    Text("已有项目", style = MaterialTheme.typography.labelLarge)
                    projects.take(8).forEach { project ->
                        AssistChip(onClick = { path = project }, label = { Text(project) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(path) }, enabled = path.startsWith('/')) {
                Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val millis = if (epochSeconds < 10_000_000_000L) epochSeconds * 1000 else epochSeconds
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}
