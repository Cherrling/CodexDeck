package cc.cherr.codexdeck.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState
import cc.cherr.codexdeck.ConnectionPhase
import cc.cherr.codexdeck.protocol.ApprovalKind
import cc.cherr.codexdeck.protocol.ApprovalRequest
import cc.cherr.codexdeck.protocol.ThreadItem
import cc.cherr.codexdeck.protocol.ThreadTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    state: AppState,
    controller: AppController,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renameVisible by remember { mutableStateOf(false) }
    var archiveVisible by remember { mutableStateOf(false) }
    val thread = state.selectedThread
    val rows = remember(state.turns) { buildTimelineRows(state.turns) }
    val listState = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(rows.size, state.isTurnRunning) {
        if (rows.isNotEmpty() && autoFollow) {
            listState.scrollToItem(rows.lastIndex)
        }
    }
    LaunchedEffect(listState, state.olderHistoryCursor) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (visibleIndex) ->
            autoFollow = visibleIndex >= rows.lastIndex - 1
            if (visibleIndex == 0 && state.olderHistoryCursor != null) {
                controller.loadOlderHistory()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回会话")
                    }
                },
                title = {
                    Column {
                        Text(
                            thread?.title ?: "Codex",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.selectedSession?.cwd ?: state.connectionMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = state.connectionPhase == ConnectionPhase.CONNECTED,
                        onClick = onOpenTerminal,
                    ) { Text("Shell", style = MaterialTheme.typography.labelLarge) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "会话操作")
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = { menu = false; renameVisible = true },
                            )
                            DropdownMenuItem(
                                text = { Text("分叉 Thread") },
                                onClick = { menu = false; controller.forkThread() },
                            )
                            DropdownMenuItem(
                                text = { Text("压缩上下文") },
                                onClick = { menu = false; controller.compactThread() },
                            )
                            DropdownMenuItem(
                                text = { Text("归档") },
                                onClick = { menu = false; archiveVisible = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                state = state,
                controller = controller,
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (state.connectionPhase != ConnectionPhase.CONNECTED) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(MaterialTheme.colorScheme.error, state.connectionMessage)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::reconnect) { Text("重连") }
                }
            }
            if (state.isLoadingThread) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.isLoadingHistory) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            ControlsBar(state, controller)
            HorizontalDivider()
            if (state.selectedSession == null && state.isLoadingThread) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (rows.isEmpty()) {
                        item {
                            EmptyPanel(
                                title = "Thread 已恢复",
                                detail = "发送第一条消息开始执行。所有 Agent 事件都来自远端 app-server。",
                            )
                        }
                    }
                    items(
                        count = rows.size,
                        key = { index -> rows[index].key },
                    ) { index ->
                        when (val row = rows[index]) {
                            is TimelineRow.TurnHeader -> TurnHeader(row.turn)
                            is TimelineRow.Entry -> ItemCard(row.item)
                        }
                    }
                }
            }
        }
    }

    if (renameVisible) {
        var name by remember(thread?.id) { mutableStateOf(thread?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameVisible = false },
            title = { Text("重命名 Thread") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        controller.renameSelectedThread(name)
                        renameVisible = false
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameVisible = false }) { Text("取消") } },
        )
    }
    if (archiveVisible) {
        ConfirmDialog(
            title = "归档此 Thread？",
            message = "Thread 会从活动列表移出，之后可在远端归档会话中恢复。",
            onDismiss = { archiveVisible = false },
            onConfirm = {
                archiveVisible = false
                controller.archiveSelectedThread()
            },
        )
    }
}

@Composable
private fun ControlsBar(state: AppState, controller: AppController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PickerChip(
            label = state.selectedModel?.displayName ?: "模型",
            options = state.models.map { it.id to it.displayName },
            onSelected = controller::setModel,
        )
        val efforts = state.selectedModel?.supportedReasoningEfforts.orEmpty()
        if (efforts.isNotEmpty()) {
            PickerChip(
                label = "推理 ${state.selectedReasoningEffort ?: "默认"}",
                options = efforts.map { it.effort to it.effort },
                onSelected = controller::setReasoningEffort,
            )
        }
        PickerChip(
            label = if (state.selectedCollaborationMode == "plan") "Plan" else "Default",
            options = state.collaborationModes.mapNotNull { mode ->
                mode.mode.takeIf(String::isNotBlank)?.let { it to (mode.name.ifBlank { it }) }
            },
            onSelected = controller::setCollaborationMode,
        )
        PickerChip(
            label = when (state.approvalPolicy) {
                "never" -> "无需审批"
                "untrusted" -> "仅信任操作"
                else -> "按需审批"
            },
            options = listOf(
                "on-request" to "按需审批",
                "untrusted" to "仅信任操作",
                "never" to "无需审批",
            ),
            onSelected = controller::setApprovalPolicy,
        )
        PickerChip(
            label = when (state.sandbox) {
                "read-only" -> "只读"
                "danger-full-access" -> "完全访问"
                else -> "工作区写入"
            },
            options = listOf(
                "workspace-write" to "工作区写入",
                "read-only" to "只读",
                "danger-full-access" to "完全访问（高风险）",
            ),
            onSelected = controller::setSandbox,
        )
        if (state.permissionProfiles.isNotEmpty()) {
            PickerChip(
                label = state.selectedPermissionProfileId ?: "权限配置",
                options = listOf<Pair<String?, String>>(null to "Legacy sandbox") +
                    state.permissionProfiles
                        .filter { it.allowed }
                        .map { it.id to it.id },
                onSelected = controller::setPermissionProfile,
                display = { value ->
                    value ?: "Legacy sandbox"
                },
            )
        }
    }
}

@Composable
private fun PickerChip(
    label: String,
    options: List<Pair<String?, String>>,
    onSelected: (String) -> Unit,
    display: (String?) -> String = { it.orEmpty() },
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1) },
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(display(value)) },
                    onClick = {
                        expanded = false
                        value?.let(onSelected)
                    },
                )
            }
        }
    }
}

@Composable
private fun Composer(state: AppState, controller: AppController) {
    var text by remember(state.selectedThreadId) { mutableStateOf("") }
    var lastEpoch by remember(state.selectedThreadId) { mutableIntStateOf(state.composerEpoch) }
    LaunchedEffect(state.composerEpoch) {
        if (state.composerEpoch != lastEpoch) {
            text = ""
            lastEpoch = state.composerEpoch
        }
    }
    Surface(tonalElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(10.dp)) {
            if (state.isTurnRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(Color(0xFFFFC857), "Agent 正在执行")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::interruptTurn) { Text("中断") }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 200_000) text = it },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 6,
                    enabled = state.connectionPhase == ConnectionPhase.CONNECTED &&
                        state.selectedSession != null,
                    placeholder = {
                        Text(if (state.isTurnRunning) "追加指令给当前 Turn…" else "发送消息给 Codex…")
                    },
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    enabled = text.isNotBlank() && !state.isSending &&
                        state.connectionPhase == ConnectionPhase.CONNECTED &&
                        state.selectedSession != null,
                    onClick = { controller.sendMessage(text) },
                    modifier = Modifier.height(56.dp),
                ) {
                    if (state.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

private sealed interface TimelineRow {
    val key: String

    data class TurnHeader(val turn: ThreadTurn) : TimelineRow {
        override val key: String = "turn:${turn.id}"
    }

    data class Entry(val item: ThreadItem) : TimelineRow {
        override val key: String = "item:${item.id}"
    }
}

private fun buildTimelineRows(turns: List<ThreadTurn>): List<TimelineRow> =
    turns.flatMap { turn ->
        buildList {
            add(TimelineRow.TurnHeader(turn))
            turn.items.forEach { add(TimelineRow.Entry(it)) }
        }
    }

@Composable
private fun TurnHeader(turn: ThreadTurn) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (turn.status) {
                "inProgress" -> "运行中"
                "completed" -> "完成"
                "failed" -> "失败"
                "interrupted" -> "已中断"
                else -> turn.status
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun ItemCard(item: ThreadItem) {
    when (item.type) {
        "userMessage" -> UserMessageCard(item)
        "agentMessage" -> AgentMessageCard(item)
        "reasoning" -> ReasoningCard(item)
        "plan" -> GenericCard("Plan", item.text, Color(0xFF8FB8FF))
        "commandExecution" -> CommandCard(item)
        "fileChange" -> FileChangeCard(item)
        "mcpToolCall", "dynamicToolCall" -> ToolCard(item)
        "functionCallOutput" -> GenericCard(
            "Tool output · ${item.raw["name"]?.stringValue().orEmpty()}",
            item.raw["output"]?.pretty().orEmpty(),
            MaterialTheme.colorScheme.tertiary,
        )
        "collabAgentToolCall" -> CollaborationCard(item)
        "subAgentActivity" -> GenericCard(
            "Subagent · ${item.raw["kind"]?.stringValue().orEmpty()}",
            item.raw.toString(),
            Color(0xFFBAA7FF),
        )
        "webSearch" -> GenericCard(
            "Web search",
            item.raw["query"]?.stringValue().orEmpty(),
            Color(0xFF79C7FF),
        )
        "imageView" -> GenericCard(
            "Image",
            item.raw["path"]?.stringValue().orEmpty(),
            MaterialTheme.colorScheme.tertiary,
        )
        "enteredReviewMode", "exitedReviewMode" -> GenericCard(
            "Code Review",
            item.raw["review"]?.stringValue().orEmpty(),
            Color(0xFFB9D47E),
        )
        "contextCompaction" -> GenericCard(
            "Context compacted",
            "历史上下文已压缩",
            MaterialTheme.colorScheme.outline,
        )
        else -> ExpandableUnknownItem(item)
    }
}

@Composable
private fun UserMessageCard(item: ThreadItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        SelectionContainer {
            Text(
                userText(item.raw),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AgentMessageCard(item: ThreadItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        MarkdownContent(
            text = item.text.ifBlank { item.raw["text"]?.stringValue().orEmpty() },
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ReasoningCard(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val summary = arrayStrings(item.raw["summary"])
        .ifEmpty { arrayStrings(item.raw["content"]) }
        .joinToString("\n\n")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 reasoning" else "查看 reasoning")
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        summary.ifBlank { "等待 reasoning 摘要…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandCard(item: ThreadItem) {
    val command = item.raw["command"]?.stringValue().orEmpty()
    val output = item.raw["aggregatedOutput"]?.stringValue().orEmpty()
    val exitCode = item.raw["exitCode"]?.let {
        runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
    }
    val status = item.status.ifBlank { item.raw["status"]?.stringValue().orEmpty() }
    ItemShell(
        title = "Command · $status" + (exitCode?.let { " · exit $it" } ?: ""),
        accent = if (status == "failed" || (exitCode != null && exitCode != 0)) {
            MaterialTheme.colorScheme.error
        } else {
            Color(0xFF79C7FF)
        },
    ) {
        CodeBlock(command)
        if (output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            CodeBlock(output)
        }
    }
}

@Composable
private fun FileChangeCard(item: ThreadItem) {
    val changes = (item.raw["changes"] as? JsonArray).orEmpty()
    ItemShell(
        title = "File changes · ${changes.size}",
        accent = Color(0xFFB9D47E),
    ) {
        changes.forEach { change ->
            val objectValue = change as? JsonObject ?: return@forEach
            Text(
                objectValue["path"]?.stringValue().orEmpty(),
                style = MaterialTheme.typography.labelLarge,
            )
            CodeBlock(objectValue["diff"]?.stringValue().orEmpty())
        }
    }
}

@Composable
private fun ToolCard(item: ThreadItem) {
    val server = item.raw["server"]?.stringValue()
    val tool = item.raw["tool"]?.stringValue() ?: item.raw["namespace"]?.stringValue().orEmpty()
    ItemShell(
        title = listOfNotNull(server, tool).joinToString(" / ").ifBlank { item.type },
        accent = MaterialTheme.colorScheme.tertiary,
    ) {
        CodeBlock(
            buildString {
                append("arguments:\n")
                append(item.raw["arguments"]?.pretty().orEmpty())
                item.raw["result"]?.takeUnless { it is JsonNull }?.let {
                    append("\n\nresult:\n")
                    append(it.pretty())
                }
                item.raw["error"]?.takeUnless { it is JsonNull }?.let {
                    append("\n\nerror:\n")
                    append(it.pretty())
                }
            },
        )
    }
}

@Composable
private fun CollaborationCard(item: ThreadItem) {
    val tool = item.raw["tool"]?.stringValue().orEmpty()
    val states = (item.raw["agentsStates"] as? JsonObject).orEmpty()
    ItemShell(
        title = "Agent · $tool · ${item.status}",
        accent = Color(0xFFBAA7FF),
    ) {
        item.raw["prompt"]?.stringValue()?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        states.forEach { (agent, value) ->
            val state = value as? JsonObject
            Text(
                "$agent · ${state?.get("status")?.stringValue().orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state?.get("message")?.stringValue()?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GenericCard(title: String, text: String, accent: Color) {
    ItemShell(title = title, accent = accent) {
        SelectionContainer {
            Text(text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ExpandableUnknownItem(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    ItemShell(title = "Unsupported item · ${item.type}", accent = MaterialTheme.colorScheme.outline) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起原始事件" else "查看原始事件")
        }
        if (expanded) CodeBlock(item.raw.toString())
    }
}

@Composable
private fun ItemShell(
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = accent)
            content()
        }
    }
}

@Composable
private fun CodeBlock(text: String) {
    val scroll = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF080B0E),
        contentColor = Color(0xFFD7E0E8),
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier
                    .horizontalScroll(scroll)
                    .padding(10.dp)
                    .heightIn(max = 520.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) { splitFences(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            if (part.isCode) {
                CodeBlock(part.text)
            } else if (part.text.isNotBlank()) {
                SelectionContainer {
                    Text(part.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private data class MarkdownPart(val text: String, val isCode: Boolean)

private fun splitFences(text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    var cursor = 0
    while (cursor < text.length) {
        val start = text.indexOf("```", cursor)
        if (start < 0) {
            parts += MarkdownPart(text.substring(cursor), false)
            break
        }
        if (start > cursor) parts += MarkdownPart(text.substring(cursor, start), false)
        val contentStart = text.indexOf('\n', start + 3).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf("```", contentStart)
        if (end < 0) {
            parts += MarkdownPart(text.substring(contentStart), true)
            break
        }
        parts += MarkdownPart(text.substring(contentStart, end).trimEnd(), true)
        cursor = end + 3
    }
    return parts
}

@Composable
fun ApprovalDialog(request: ApprovalRequest, controller: AppController) {
    val questions = remember(request.requestId.wireValue) { approvalQuestions(request.params) }
    var answers by remember(request.requestId.wireValue) {
        mutableStateOf(questions.associate { it.id to "" })
    }
    val highRisk = request.detail.contains("sudo", true) ||
        request.detail.contains("rm ", true) ||
        request.kind == ApprovalKind.PERMISSIONS
    AlertDialog(
        onDismissRequest = { controller.rejectApproval(request) },
        title = { Text(request.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(request.detail, style = MaterialTheme.typography.bodyMedium)
                if (highRisk) {
                    Text(
                        "此操作可能扩大远端执行权限。请确认 Host 与工作目录。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                request.params.toString().take(4_000).let { raw ->
                    if (request.kind != ApprovalKind.USER_INPUT) CodeBlock(raw)
                }
                questions.forEach { question ->
                    Text(question.question, style = MaterialTheme.typography.titleSmall)
                    if (question.options.isNotEmpty()) {
                        question.options.forEach { option ->
                            FilterChip(
                                selected = answers[question.id] == option,
                                onClick = { answers = answers + (question.id to option) },
                                label = { Text(option) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = answers[question.id].orEmpty(),
                        onValueChange = { answers = answers + (question.id to it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (request.kind == ApprovalKind.USER_INPUT) {
                TextButton(onClick = {
                    controller.respondToApproval(
                        request,
                        "accept",
                        answers.mapValues { listOf(it.value) },
                    )
                }) { Text("提交") }
            } else {
                Column {
                    TextButton(onClick = {
                        controller.respondToApproval(request, "accept")
                    }) { Text("允许一次") }
                    if (request.kind in setOf(
                            ApprovalKind.COMMAND,
                            ApprovalKind.FILE_CHANGE,
                            ApprovalKind.PERMISSIONS,
                        )
                    ) {
                        TextButton(onClick = {
                            controller.respondToApproval(request, "acceptForSession")
                        }) { Text("本会话允许") }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { controller.rejectApproval(request) }) { Text("拒绝") }
        },
    )
}

private data class ApprovalQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
)

private fun approvalQuestions(raw: JsonObject): List<ApprovalQuestion> {
    val array = raw["questions"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val question = element as? JsonObject ?: return@mapNotNull null
        val id = question["id"]?.stringValue() ?: return@mapNotNull null
        ApprovalQuestion(
            id = id,
            question = question["question"]?.stringValue().orEmpty(),
            options = (question["options"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("label")?.stringValue()
            },
        )
    }
}

private fun userText(raw: JsonObject): String {
    val content = raw["content"] as? JsonArray ?: return raw["text"]?.stringValue().orEmpty()
    return content.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        when (item["type"]?.stringValue()) {
            "text" -> item["text"]?.stringValue()
            "image", "localImage" -> "[图片]"
            "skill" -> "\$${item["name"]?.stringValue().orEmpty()}"
            "mention" -> "@${item["name"]?.stringValue().orEmpty()}"
            else -> item.toString()
        }
    }.joinToString("\n")
}

private fun arrayStrings(element: JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().mapNotNull { it.stringValue() }

private fun JsonElement.stringValue(): String? =
    runCatching { jsonPrimitive.contentOrNull }.getOrNull()

private fun JsonElement.pretty(): String = try {
    toString()
} catch (_: Throwable) {
    toString()
}
