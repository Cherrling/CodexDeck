package cc.cherr.codexdeck.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState
import cc.cherr.codexdeck.ConnectionPhase
import cc.cherr.codexdeck.approvalKey
import cc.cherr.codexdeck.ssh.usesSshTransport
import cc.cherr.codexdeck.protocol.CapabilitySnapshot
import cc.cherr.codexdeck.protocol.ApprovalKind
import cc.cherr.codexdeck.protocol.ApprovalRequest
import cc.cherr.codexdeck.protocol.ThreadItem
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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
    val expandedProcessTurns = remember(state.selectedThreadId) {
        mutableStateMapOf<String, Boolean>()
    }
    val expandedTurnIds = expandedProcessTurns
        .filterValues { it }
        .keys
        .toSet()
    val rows = remember(state.turns, expandedTurnIds) {
        buildTimelineRows(state.turns, expandedTurnIds)
    }
    val listState = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }
    var historyRequestedDuringDrag by remember(state.selectedThreadId) {
        mutableStateOf(false)
    }
    val rowsLastIndex by rememberUpdatedState(rows.lastIndex)
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val showScrollToBottom by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }
    val scrollScope = rememberCoroutineScope()

    LaunchedEffect(state.turns) {
        if (rows.isNotEmpty() && autoFollow) {
            listState.scrollToItem(rows.lastIndex)
        }
    }
    LaunchedEffect(
        listState,
        isDragged,
        state.olderHistoryCursor,
        state.olderTurnCursor,
        state.isLoadingHistory,
    ) {
        if (!isDragged) historyRequestedDuringDrag = false
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (visibleIndex) ->
            if (isDragged) {
                autoFollow = visibleIndex >= rowsLastIndex - 1
            }
            if (
                isDragged &&
                !state.isLoadingHistory &&
                !historyRequestedDuringDrag &&
                visibleIndex == 0 &&
                (state.olderHistoryCursor != null || state.olderTurnCursor != null)
            ) {
                historyRequestedDuringDrag = true
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回会话")
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
                            listOfNotNull(
                                state.activeHost?.label,
                                state.selectedSession?.cwd ?: state.connectionMessage,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = state.connectionPhase == ConnectionPhase.CONNECTED &&
                            state.activeHost?.usesSshTransport() == true,
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
                                text = { Text("Review 未提交变更") },
                                onClick = {
                                    menu = false
                                    controller.reviewUncommittedChanges()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Skills / MCP / Plugins") },
                                onClick = {
                                    menu = false
                                    controller.loadCapabilities()
                                },
                            )
                            if (state.showArchivedThreads) {
                                DropdownMenuItem(
                                    text = { Text("取消归档") },
                                    onClick = {
                                        menu = false
                                        controller.unarchiveSelectedThread()
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("归档") },
                                    onClick = { menu = false; archiveVisible = true },
                                )
                            }
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
                Box(Modifier.fillMaxSize()) {
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
                                is TimelineRow.Message -> {
                                    when (row.role) {
                                        TimelineMessageRole.USER -> UserMessageCard(row.item)
                                        TimelineMessageRole.ANSWER -> AgentMessageCard(row.item)
                                    }
                                }
                                is TimelineRow.TurnSummary -> TurnProcessSummary(
                                    row = row,
                                    onToggle = {
                                        expandedProcessTurns[row.turn.id] = !row.expanded
                                    },
                                )
                                is TimelineRow.ProcessEntry -> ItemCard(row.item, row.turnStatus)
                                is TimelineRow.TurnDiff -> TurnDiffRow(row.diff)
                            }
                        }
                    }
                    if (showScrollToBottom && rows.isNotEmpty()) {
                        SmallFloatingActionButton(
                            onClick = {
                                autoFollow = true
                                scrollScope.launch {
                                    listState.animateScrollToItem(rows.lastIndex)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "回到底部",
                            )
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
    if (state.isLoadingCapabilities || state.capabilities != null) {
        CapabilityDialog(
            snapshot = state.capabilities,
            loading = state.isLoadingCapabilities,
            onDismiss = controller::dismissCapabilities,
        )
    }
}

@Composable
private fun CapabilityDialog(
    snapshot: CapabilitySnapshot?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex Capabilities") },
        text = {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (snapshot != null) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            snapshot.cwd,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    snapshot.unavailable.forEach { (capability, error) ->
                        item(key = "unavailable:$capability") {
                            GenericCard(
                                "$capability unavailable",
                                error,
                                MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
                        Text(
                            "Skills · ${snapshot.skills.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (snapshot.skills.isEmpty()) {
                        item { Text("未发现 Skills") }
                    }
                    snapshot.skills.forEach { skill ->
                        item(key = "skill:${skill.path}:${skill.name}") {
                            GenericCard(
                                "${skill.name} · ${skill.scope}" +
                                    if (skill.enabled) "" else " · disabled",
                                skill.description,
                                MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    item {
                        Text(
                            "MCP · ${snapshot.mcpServers.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (snapshot.mcpServers.isEmpty()) {
                        item { Text("未发现 MCP Server") }
                    }
                    snapshot.mcpServers.forEach { server ->
                        item(key = "mcp:${server.name}") {
                            GenericCard(
                                server.name,
                                "status: ${server.runtimeStatus}\n" +
                                    "auth: ${server.authStatus}\n" +
                                    "tools: ${server.toolCount}",
                                MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    item {
                        Text(
                            "Plugins · ${snapshot.plugins.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (snapshot.plugins.isEmpty()) {
                        item { Text("未发现 Plugins") }
                    }
                    snapshot.plugins.forEach { plugin ->
                        item(key = "plugin:${plugin.id}") {
                            GenericCard(
                                plugin.name +
                                    if (plugin.enabled) "" else " · disabled",
                                listOfNotNull(
                                    plugin.description.takeIf(String::isNotBlank),
                                    "installed: ${plugin.installed}",
                                    plugin.version?.let { "version: $it" },
                                ).joinToString("\n"),
                                Color(0xFFBAA7FF),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
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
            onSelected = { value -> value?.let(controller::setModel) },
        )
        val efforts = state.selectedModel?.supportedReasoningEfforts.orEmpty()
        if (efforts.isNotEmpty()) {
            PickerChip(
                label = "推理 ${state.selectedReasoningEffort ?: "默认"}",
                options = efforts.map { it.effort to it.effort },
                onSelected = { value -> value?.let(controller::setReasoningEffort) },
            )
        }
        PickerChip(
            label = if (state.selectedCollaborationMode == "plan") "Plan" else "Default",
            options = state.collaborationModes.mapNotNull { mode ->
                mode.mode.takeIf(String::isNotBlank)?.let { it to (mode.name.ifBlank { it }) }
            },
            onSelected = { value -> value?.let(controller::setCollaborationMode) },
        )
        PickerChip(
            label = approvalPolicyOptionLabel(state.approvalPolicy),
            options = approvalPolicyOptions.map { it.value to it.label },
            onSelected = { value -> value?.let(controller::setApprovalPolicy) },
        )
        PickerChip(
            label = approvalsReviewerOptionLabel(state.approvalsReviewer),
            options = approvalsReviewerOptions.map { it.value to it.label },
            onSelected = { value -> value?.let(controller::setApprovalsReviewer) },
        )
        val permissionPicker = permissionPickerOptions(
            profiles = state.permissionProfiles,
            selectedProfileId = state.selectedPermissionProfileId,
            sandboxMode = state.sandbox,
        )
        PickerChip(
            label = permissionPicker.label,
            options = permissionPicker.options.map { it.value to it.label },
            onSelected = { value ->
                value?.let {
                    if (permissionPicker.profilesSupported) {
                        controller.setPermissionProfile(it)
                    } else {
                        controller.setSandbox(it)
                    }
                }
            },
            enabled = permissionPicker.options.isNotEmpty(),
        )
    }
}

@Composable
private fun PickerChip(
    label: String,
    options: List<Pair<String?, String>>,
    onSelected: (String?) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { if (enabled) expanded = true },
            label = { Text(label, maxLines = 1) },
            enabled = enabled,
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun Composer(state: AppState, controller: AppController) {
    var text by remember(state.selectedThreadId) { mutableStateOf("") }
    var images by remember(state.selectedThreadId) { mutableStateOf<List<SelectedImage>>(emptyList()) }
    var attachmentError by remember(state.selectedThreadId) { mutableStateOf<String?>(null) }
    var lastEpoch by remember(state.selectedThreadId) { mutableIntStateOf(state.composerEpoch) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachmentError = null
        scope.launch {
            try {
                val remaining = (4 - images.size).coerceAtLeast(0)
                require(remaining > 0) { "每条消息最多附加 4 张图片" }
                val loaded = withContext(Dispatchers.IO) {
                    uris.take(remaining).map { readImageAttachment(context, it) }
                }
                images = images + loaded
            } catch (failure: Throwable) {
                attachmentError = failure.message ?: "读取图片失败"
            }
        }
    }
    LaunchedEffect(state.composerEpoch) {
        if (state.composerEpoch != lastEpoch) {
            text = ""
            images = emptyList()
            attachmentError = null
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
            if (images.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    images.forEachIndexed { index, image ->
                        AssistChip(
                            onClick = {
                                images = images.filterIndexed { itemIndex, _ -> itemIndex != index }
                            },
                            label = {
                                Text(
                                    "${image.name} ×",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            attachmentError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = state.connectionPhase == ConnectionPhase.CONNECTED &&
                        state.selectedSession != null &&
                        !state.isLoadingThread &&
                        !state.isSending,
                ) { Text("图片") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 200_000) text = it },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 6,
                    enabled = state.connectionPhase == ConnectionPhase.CONNECTED &&
                        state.selectedSession != null &&
                        !state.isLoadingThread,
                    placeholder = {
                        Text(if (state.isTurnRunning) "追加指令给当前 Turn…" else "发送消息给 Codex…")
                    },
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    enabled = (text.isNotBlank() || images.isNotEmpty()) && !state.isSending &&
                        state.connectionPhase == ConnectionPhase.CONNECTED &&
                        state.selectedSession != null &&
                        !state.isLoadingThread,
                    onClick = {
                        controller.sendMessage(text, images.map(SelectedImage::dataUrl))
                    },
                    modifier = Modifier.height(56.dp),
                ) {
                    if (state.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

private enum class ProcessGlyphType {
    THINKING,
    TERMINAL,
    EDIT,
    FILE,
    TOOL,
    AGENT,
    WEB,
    PLAN,
    MESSAGE,
    INFO,
    CHECK,
    ERROR,
    SPINNER,
}

@Composable
private fun TurnProcessSummary(
    row: TimelineRow.TurnSummary,
    onToggle: () -> Unit,
) {
    val turn = row.turn
    val failed = turn.status == "failed"
    val active = turn.status == "inProgress"
    val canToggle = turn.status == "completed" && row.hasDetails
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        ProcessRowFrame(
            glyph = when {
                active -> ProcessGlyphType.SPINNER
                failed -> ProcessGlyphType.ERROR
                turn.status == "interrupted" -> ProcessGlyphType.INFO
                else -> ProcessGlyphType.CHECK
            },
            title = turnSummaryLabel(turn.status, row.stats),
            titleColor = if (active || failed) accent else MaterialTheme.colorScheme.onSurface,
            accent = accent,
            active = active,
            expanded = if (canToggle) row.expanded else null,
            emphasized = true,
            showRail = false,
            onClick = if (canToggle) onToggle else null,
        )
        turn.error?.message?.takeIf(String::isNotBlank)?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(start = 40.dp, end = 8.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TurnDiffRow(diff: String) {
    var expanded by remember(diff) { mutableStateOf(false) }
    val files = remember(diff) {
        diff.lineSequence().count { it.startsWith("diff --git ") }.coerceAtLeast(1)
    }
    ProcessRowFrame(
        glyph = ProcessGlyphType.EDIT,
        title = "本轮变更",
        summary = "$files 个文件",
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(boundedProcessText(diff, maxChars = 48_000))
    }
}

@Composable
private fun ItemCard(item: ThreadItem, turnStatus: String) {
    when (item.type) {
        "userMessage" -> UserMessageCard(item)
        "agentMessage" -> IntermediateAgentMessageRow(item)
        "reasoning" -> ReasoningRow(item, turnStatus)
        "plan" -> PlanRow(item)
        "commandExecution" -> CommandRow(item, turnStatus)
        "fileChange" -> FileChangeRow(item)
        "mcpToolCall", "dynamicToolCall" -> ToolRow(item, turnStatus)
        "functionCallOutput" -> FunctionOutputRow(item)
        "collabAgentToolCall" -> CollaborationRow(item, turnStatus)
        "subAgentActivity" -> SubagentActivityRow(item)
        "webSearch" -> WebSearchRow(item)
        "imageView" -> ImageViewRow(item)
        "enteredReviewMode", "exitedReviewMode" -> ReviewModeRow(item)
        "contextCompaction" -> SimpleProcessRow(
            glyph = ProcessGlyphType.INFO,
            title = "上下文已压缩",
            summary = "历史已整理为可继续使用的上下文",
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
private fun ReasoningRow(item: ThreadItem, turnStatus: String) {
    var expandedOverride by remember(item.id) { mutableStateOf<Boolean?>(null) }
    val text = remember(item.raw) { reasoningTextOf(item) }
    val preview = remember(text) { reasoningPreview(text) }
    val active = item.status == "inProgress" ||
        (item.status.isBlank() && turnStatus == "inProgress")
    val expanded = expandedOverride ?: active
    val label = if (active) "思考中" else "思考"
    val summary = when {
        preview.isNotBlank() -> preview
        active -> null
        else -> "提供方未返回思考内容"
    }
    ProcessRowFrame(
        glyph = ProcessGlyphType.THINKING,
        title = label,
        summary = summary,
        accent = MaterialTheme.colorScheme.primary,
        active = active,
        expanded = expanded,
        onClick = { expandedOverride = !expanded },
    ) {
        when {
            active -> ReasoningLiveWindow(text)
            text.isBlank() -> Text(
                "本次思考已结束，但模型或第三方提供商没有返回可见内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> MarkdownContent(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
        }
    }
}

@Composable
private fun ReasoningLiveWindow(text: String) {
    val visible = remember(text) { streamingReasoningTail(text) }
    Text(
        visible.ifBlank { "正在思考…" },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(top = 2.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PlanRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val text = item.text
    ProcessRowFrame(
        glyph = ProcessGlyphType.PLAN,
        title = "计划",
        summary = reasoningPreview(text).ifBlank { "查看当前执行计划" },
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        if (text.isBlank()) {
            Text("服务端尚未返回计划内容。")
        } else {
            MarkdownContent(text = text, compact = true)
        }
    }
}

@Composable
private fun IntermediateAgentMessageRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val text = item.text.ifBlank { item.raw["text"]?.stringValue().orEmpty() }
    ProcessRowFrame(
        glyph = ProcessGlyphType.MESSAGE,
        title = "消息",
        summary = reasoningPreview(text).ifBlank { "过程消息" },
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        MarkdownContent(text = text.ifBlank { "—" }, compact = true)
    }
}

@Composable
private fun CommandRow(item: ThreadItem, turnStatus: String) {
    var expandedOverride by remember(item.id) { mutableStateOf<Boolean?>(null) }
    val command = item.raw["command"]?.stringValue().orEmpty()
    val output = item.raw["aggregatedOutput"]?.stringValue().orEmpty()
    val exitCode = item.raw["exitCode"]?.let {
        runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
    }
    val status = item.status.ifBlank { item.raw["status"]?.stringValue().orEmpty() }
    val failed = status == "failed" || status == "declined" ||
        (exitCode != null && exitCode != 0)
    val active = status == "inProgress" ||
        (status.isBlank() && turnStatus == "inProgress")
    val expanded = expandedOverride ?: failed
    val summary = remember(command) { commandExecutionSummary(command) }
    ProcessRowFrame(
        glyph = ProcessGlyphType.TERMINAL,
        title = "运行",
        summary = summary,
        accent = if (failed) MaterialTheme.colorScheme.error else Color(0xFF79C7FF),
        active = active,
        expanded = expanded,
        onClick = { expandedOverride = !expanded },
        trailingText = when {
            exitCode != null && exitCode != 0 -> "exit $exitCode"
            failed -> commandExecutionStatusLabel(status)
            else -> null
        },
        trailingColor = if (failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        if (command.isNotBlank()) {
            CodeBlock(boundedProcessText(command, maxChars = 8_000))
        }
        if (output.isNotBlank()) {
            if (command.isNotBlank()) Spacer(Modifier.height(6.dp))
            CodeBlock(
                boundedProcessText(
                    text = output,
                    maxChars = 32_000,
                    keepTail = true,
                ),
            )
        }
    }
}

internal fun commandExecutionSummary(command: String): String =
    command.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(180)
        .orEmpty()
        .ifBlank { "（无命令文本）" }

internal fun commandExecutionStatusLabel(status: String): String = when (status) {
    "inProgress" -> "运行中"
    "completed" -> "完成"
    "failed" -> "失败"
    "declined" -> "已拒绝"
    "interrupted" -> "已中断"
    else -> status.ifBlank { "命令" }
}

@Composable
private fun FileChangeRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val changes = (item.raw["changes"] as? JsonArray).orEmpty()
    val summary = remember(item.raw) {
        var added = 0
        var removed = 0
        changes.forEach { change ->
            val diff = (change as? JsonObject)?.get("diff")?.stringValue().orEmpty()
            diff.lineSequence().forEach { line ->
                when {
                    line.startsWith("+++") || line.startsWith("---") -> Unit
                    line.startsWith("+") -> added++
                    line.startsWith("-") -> removed++
                }
            }
        }
        "${changes.size} 个文件 · +$added −$removed"
    }
    ProcessRowFrame(
        glyph = ProcessGlyphType.EDIT,
        title = "修改",
        summary = summary,
        accent = Color(0xFF9ACD77),
        active = item.status == "inProgress",
        expanded = expanded,
        onClick = { expanded = !expanded },
        trailingText = commandExecutionStatusLabel(item.status).takeIf {
            item.status == "failed" || item.status == "declined"
        },
        trailingColor = MaterialTheme.colorScheme.error,
    ) {
        if (changes.isEmpty()) {
            Text("服务端未返回文件变更详情。")
        }
        changes.forEachIndexed { index, change ->
            val objectValue = change as? JsonObject ?: return@forEachIndexed
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                objectValue["path"]?.stringValue().orEmpty().ifBlank { "未命名文件" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(5.dp))
            CodeBlock(
                boundedProcessText(
                    objectValue["diff"]?.stringValue().orEmpty(),
                    maxChars = 36_000,
                ),
            )
        }
    }
}

@Composable
private fun ToolRow(item: ThreadItem, turnStatus: String) {
    var expandedOverride by remember(item.id) { mutableStateOf<Boolean?>(null) }
    val server = item.raw["server"]?.stringValue()
    val tool = item.raw["tool"]?.stringValue() ?: item.raw["namespace"]?.stringValue().orEmpty()
    val failed = item.status == "failed" || item.status == "declined"
    val active = item.status == "inProgress" ||
        (item.status.isBlank() && turnStatus == "inProgress")
    val expanded = expandedOverride ?: failed
    ProcessRowFrame(
        glyph = ProcessGlyphType.TOOL,
        title = "调用",
        summary = listOfNotNull(server, tool).joinToString(" / ").ifBlank { item.type },
        accent = MaterialTheme.colorScheme.tertiary,
        active = active,
        expanded = expanded,
        onClick = { expandedOverride = !expanded },
        trailingText = commandExecutionStatusLabel(item.status).takeIf { failed },
        trailingColor = MaterialTheme.colorScheme.error,
    ) {
        CodeBlock(
            boundedProcessText(
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
                maxChars = 40_000,
            ),
        )
    }
}

@Composable
private fun FunctionOutputRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val output = item.raw["output"]?.pretty().orEmpty()
    ProcessRowFrame(
        glyph = ProcessGlyphType.TOOL,
        title = "输出",
        summary = reasoningPreview(output).ifBlank {
            item.raw["name"]?.stringValue().orEmpty().ifBlank { "工具输出" }
        },
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(
            boundedProcessText(
                text = output,
                maxChars = 40_000,
                keepTail = true,
            ),
        )
    }
}

@Composable
private fun CollaborationRow(item: ThreadItem, turnStatus: String) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val tool = item.raw["tool"]?.stringValue().orEmpty().ifBlank { "Agent" }
    val states = (item.raw["agentsStates"] as? JsonObject).orEmpty()
    val active = item.status == "inProgress" ||
        (item.status.isBlank() && turnStatus == "inProgress")
    ProcessRowFrame(
        glyph = ProcessGlyphType.AGENT,
        title = "子代理",
        summary = "$tool · ${commandExecutionStatusLabel(item.status)}",
        accent = Color(0xFFBAA7FF),
        active = active,
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        item.raw["prompt"]?.stringValue()?.takeIf(String::isNotBlank)?.let {
            SelectionContainer {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(7.dp))
        }
        states.forEach { (agent, value) ->
            val state = value as? JsonObject
            Text(
                "$agent · ${state?.get("status")?.stringValue().orEmpty()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state?.get("message")?.stringValue()?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubagentActivityRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val kind = item.raw["kind"]?.stringValue().orEmpty().ifBlank { item.status }
    ProcessRowFrame(
        glyph = ProcessGlyphType.AGENT,
        title = "子代理",
        summary = kind,
        accent = Color(0xFFBAA7FF),
        active = item.status == "inProgress",
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(boundedProcessText(item.raw.pretty(), maxChars = 32_000))
    }
}

@Composable
private fun WebSearchRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val query = item.raw["query"]?.stringValue().orEmpty()
    ProcessRowFrame(
        glyph = ProcessGlyphType.WEB,
        title = "搜索",
        summary = reasoningPreview(query).ifBlank { "网页搜索" },
        accent = Color(0xFF79C7FF),
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(boundedProcessText(item.raw.pretty(), maxChars = 32_000))
    }
}

@Composable
private fun ImageViewRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    ProcessRowFrame(
        glyph = ProcessGlyphType.FILE,
        title = "图片",
        summary = item.raw["path"]?.stringValue().orEmpty().ifBlank { "查看图片信息" },
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(boundedProcessText(item.raw.pretty(), maxChars = 32_000))
    }
}

@Composable
private fun ReviewModeRow(item: ThreadItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val review = item.raw["review"]?.stringValue().orEmpty()
    ProcessRowFrame(
        glyph = ProcessGlyphType.CHECK,
        title = if (item.type == "enteredReviewMode") "代码审查" else "审查结束",
        summary = reasoningPreview(review).ifBlank { "Code Review" },
        accent = Color(0xFFB9D47E),
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(boundedProcessText(item.raw.pretty(), maxChars = 32_000))
    }
}

@Composable
private fun SimpleProcessRow(
    glyph: ProcessGlyphType,
    title: String,
    summary: String? = null,
) {
    ProcessRowFrame(
        glyph = glyph,
        title = title,
        summary = summary,
    )
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
    ProcessRowFrame(
        glyph = ProcessGlyphType.INFO,
        title = "其他事件",
        summary = item.type,
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        CodeBlock(boundedProcessText(item.raw.pretty(), maxChars = 32_000))
    }
}

@Composable
private fun ProcessRowFrame(
    glyph: ProcessGlyphType,
    title: String,
    summary: String? = null,
    accent: Color? = null,
    titleColor: Color? = null,
    active: Boolean = false,
    expanded: Boolean? = null,
    emphasized: Boolean = false,
    showRail: Boolean = true,
    trailingText: String? = null,
    trailingColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val resolvedAccent = accent ?: MaterialTheme.colorScheme.onSurfaceVariant
    val resolvedTitleColor = titleColor ?: if (emphasized) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val railColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (showRail) {
                        val x = 16.dp.toPx()
                        drawLine(
                            color = railColor,
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 38.dp)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProcessGlyph(glyph, resolvedAccent)
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                maxLines = 1,
                style = if (emphasized) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = resolvedTitleColor,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    "·",
                    modifier = Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (resolvedAccent == MaterialTheme.colorScheme.error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                    },
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            trailingText?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = trailingColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active && glyph != ProcessGlyphType.SPINNER) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = resolvedAccent,
                )
            }
            if (expanded != null) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .padding(start = 3.dp)
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            } else {
                Spacer(Modifier.width(4.dp))
            }
        }
        if (expanded == true && content != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = 16.dp.toPx()
                        drawLine(
                            color = railColor,
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    .padding(start = 40.dp, end = 6.dp, top = 2.dp, bottom = 9.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ProcessGlyph(type: ProcessGlyphType, color: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.11f)),
        contentAlignment = Alignment.Center,
    ) {
        if (type == ProcessGlyphType.SPINNER) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = color,
                strokeWidth = 1.5.dp,
            )
        } else {
            Canvas(Modifier.size(14.dp)) {
                val stroke = 1.45.dp.toPx()
                val w = size.width
                val h = size.height
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(x1 * w, y1 * h),
                        end = androidx.compose.ui.geometry.Offset(x2 * w, y2 * h),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                when (type) {
                    ProcessGlyphType.THINKING -> {
                        drawCircle(color, radius = 2.1.dp.toPx(), center = center)
                        line(0.5f, 0.02f, 0.5f, 0.2f)
                        line(0.5f, 0.8f, 0.5f, 0.98f)
                        line(0.02f, 0.5f, 0.2f, 0.5f)
                        line(0.8f, 0.5f, 0.98f, 0.5f)
                    }
                    ProcessGlyphType.TERMINAL -> {
                        line(0.12f, 0.23f, 0.4f, 0.5f)
                        line(0.4f, 0.5f, 0.12f, 0.77f)
                        line(0.5f, 0.78f, 0.88f, 0.78f)
                    }
                    ProcessGlyphType.EDIT -> {
                        line(0.18f, 0.82f, 0.72f, 0.28f)
                        line(0.72f, 0.28f, 0.84f, 0.16f)
                        line(0.18f, 0.82f, 0.34f, 0.77f)
                    }
                    ProcessGlyphType.FILE -> {
                        val path = Path().apply {
                            moveTo(w * 0.22f, h * 0.08f)
                            lineTo(w * 0.62f, h * 0.08f)
                            lineTo(w * 0.82f, h * 0.3f)
                            lineTo(w * 0.82f, h * 0.9f)
                            lineTo(w * 0.22f, h * 0.9f)
                            close()
                        }
                        drawPath(
                            path,
                            color,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                        line(0.62f, 0.08f, 0.62f, 0.3f)
                        line(0.62f, 0.3f, 0.82f, 0.3f)
                    }
                    ProcessGlyphType.TOOL -> {
                        drawCircle(
                            color,
                            radius = 2.5.dp.toPx(),
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                        line(0.5f, 0.02f, 0.5f, 0.2f)
                        line(0.5f, 0.8f, 0.5f, 0.98f)
                        line(0.02f, 0.5f, 0.2f, 0.5f)
                        line(0.8f, 0.5f, 0.98f, 0.5f)
                    }
                    ProcessGlyphType.AGENT -> {
                        drawCircle(
                            color,
                            radius = 2.2.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.35f),
                        )
                        drawCircle(
                            color,
                            radius = 2.2.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.65f),
                        )
                        line(0.44f, 0.43f, 0.58f, 0.58f)
                    }
                    ProcessGlyphType.WEB -> {
                        drawCircle(
                            color,
                            radius = w * 0.39f,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                        line(0.11f, 0.5f, 0.89f, 0.5f)
                        line(0.5f, 0.11f, 0.5f, 0.89f)
                        line(0.25f, 0.22f, 0.75f, 0.22f)
                        line(0.25f, 0.78f, 0.75f, 0.78f)
                    }
                    ProcessGlyphType.PLAN -> {
                        listOf(0.2f, 0.5f, 0.8f).forEach { y ->
                            drawCircle(
                                color,
                                radius = 1.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(w * 0.12f, h * y),
                            )
                            line(0.3f, y, 0.9f, y)
                        }
                    }
                    ProcessGlyphType.MESSAGE -> {
                        val path = Path().apply {
                            moveTo(w * 0.1f, h * 0.16f)
                            lineTo(w * 0.9f, h * 0.16f)
                            lineTo(w * 0.9f, h * 0.7f)
                            lineTo(w * 0.48f, h * 0.7f)
                            lineTo(w * 0.28f, h * 0.9f)
                            lineTo(w * 0.28f, h * 0.7f)
                            lineTo(w * 0.1f, h * 0.7f)
                            close()
                        }
                        drawPath(
                            path,
                            color,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                    }
                    ProcessGlyphType.INFO -> {
                        drawCircle(
                            color,
                            radius = w * 0.4f,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                        drawCircle(
                            color,
                            radius = 1.2.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.32f),
                        )
                        line(0.5f, 0.48f, 0.5f, 0.72f)
                    }
                    ProcessGlyphType.CHECK -> {
                        line(0.12f, 0.52f, 0.4f, 0.78f)
                        line(0.4f, 0.78f, 0.9f, 0.24f)
                    }
                    ProcessGlyphType.ERROR -> {
                        line(0.2f, 0.2f, 0.8f, 0.8f)
                        line(0.8f, 0.2f, 0.2f, 0.8f)
                    }
                    ProcessGlyphType.SPINNER -> Unit
                }
            }
        }
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
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
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
                    .heightIn(max = 520.dp)
                    .verticalScroll(vertical)
                    .horizontalScroll(horizontal)
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val markdownState = rememberMarkdownState(
        content = text,
        retainState = true,
    )
    val bodyStyle = if (compact) {
        MaterialTheme.typography.bodyMedium
    } else {
        MaterialTheme.typography.bodyLarge
    }
    SelectionContainer {
        Markdown(
            markdownState = markdownState,
            modifier = modifier.fillMaxWidth(),
            colors = markdownColor(
                text = MaterialTheme.colorScheme.onSurface,
                codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
                tableBackground = MaterialTheme.colorScheme.surfaceContainer,
            ),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.headlineLarge,
                h2 = MaterialTheme.typography.headlineMedium,
                h3 = MaterialTheme.typography.headlineSmall,
                h4 = MaterialTheme.typography.titleLarge,
                h5 = MaterialTheme.typography.titleMedium,
                h6 = MaterialTheme.typography.titleSmall,
                text = bodyStyle,
                code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                quote = bodyStyle,
                paragraph = bodyStyle,
                ordered = bodyStyle,
                bullet = bodyStyle,
                list = bodyStyle,
                table = MaterialTheme.typography.bodyMedium,
            ),
        )
    }
}

private data class SelectedImage(val name: String, val dataUrl: String)

private fun readImageAttachment(context: Context, uri: Uri): SelectedImage {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
        ?.lowercase()
        ?.takeIf { it.startsWith("image/") }
        ?: "image/jpeg"
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
        ?.filterNot(Char::isISOControl)
        ?.take(80)
        ?.takeIf(String::isNotBlank)
        ?: "image"
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    try {
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 5 * 1024 * 1024) {
                    "单张图片不能超过 5 MiB"
                }
                bytes.write(buffer, 0, count)
            }
        } ?: error("无法读取图片 $name")
        val raw = bytes.toByteArray()
        try {
            val encoded = Base64.encodeToString(raw, Base64.NO_WRAP)
            return SelectedImage(name, "data:$mimeType;base64,$encoded")
        } finally {
            raw.fill(0)
        }
    } finally {
        buffer.fill(0)
        bytes.reset()
    }
}

@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    controller: AppController,
    responding: Boolean,
) {
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
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
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
                }, enabled = !responding) { Text(if (responding) "提交中…" else "提交") }
            } else {
                Column {
                    TextButton(onClick = {
                        controller.respondToApproval(request, "accept")
                    }, enabled = !responding) { Text("允许一次") }
                    if (request.kind in setOf(
                            ApprovalKind.COMMAND,
                            ApprovalKind.FILE_CHANGE,
                            ApprovalKind.PERMISSIONS,
                        )
                    ) {
                        TextButton(onClick = {
                            controller.respondToApproval(request, "acceptForSession")
                        }, enabled = !responding) { Text("本会话允许") }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { controller.rejectApproval(request) },
                enabled = !responding,
            ) { Text("拒绝") }
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

private fun JsonElement.stringValue(): String? =
    runCatching { jsonPrimitive.contentOrNull }.getOrNull()

private fun JsonElement.pretty(): String = try {
    toString()
} catch (_: Throwable) {
    toString()
}
