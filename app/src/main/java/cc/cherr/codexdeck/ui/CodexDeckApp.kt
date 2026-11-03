package cc.cherr.codexdeck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.ConnectionPhase
import cc.cherr.codexdeck.approvalKey
import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.groupThreads

enum class MainSection {
    HOSTS,
    SESSIONS,
    SETTINGS,
    IDENTITIES,
    DIAGNOSTICS,
}

@Composable
fun CodexDeckApp(controller: AppController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.HOSTS) }
    var hostEditorVisible by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<HostRecord?>(null) }
    var deletingHost by remember { mutableStateOf<HostRecord?>(null) }
    var deletingIdentityId by remember { mutableStateOf<String?>(null) }
    var showImportIdentity by remember { mutableStateOf(false) }
    var showGenerateIdentity by remember { mutableStateOf(false) }
    var newThreadProject by remember { mutableStateOf<String?>(null) }

    val terminal = state.terminalSessions.firstOrNull { it.id == state.selectedTerminalId }
    when {
        terminal != null -> {
            BackHandler {
                controller.selectTerminal(null)
            }
            TerminalScreen(
                state = state,
                terminal = terminal,
                onBack = { controller.selectTerminal(null) },
                onSelectTerminal = controller::selectTerminal,
                onCloseTerminal = controller::closeTerminal,
            )
        }
        state.selectedThreadId != null -> {
            BackHandler { controller.closeThread() }
            ChatScreen(
                state = state,
                controller = controller,
                onBack = controller::closeThread,
                onOpenTerminal = {
                    state.activeHostId?.let(controller::openTerminal)
                },
            )
        }
        else -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                floatingActionButton = {
                    if (!state.busy && (section == MainSection.HOSTS || section == MainSection.IDENTITIES)) {
                        FloatingActionButton(onClick = {
                            if (section == MainSection.IDENTITIES) {
                                showImportIdentity = true
                            } else {
                                editingHost = null
                                hostEditorVisible = true
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
                    }
                },
                bottomBar = {
                    if (section != MainSection.IDENTITIES && section != MainSection.DIAGNOSTICS) {
                        NavigationBar {
                            MainNavigation.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = section == item.section,
                                    onClick = { section = item.section },
                                    icon = { Icon(item.icon(), contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets)) {
                    when (section) {
                        MainSection.HOSTS -> HostsScreen(
                            state = state,
                            controller = controller,
                            onAdd = {
                                editingHost = null
                                hostEditorVisible = true
                            },
                            onEdit = {
                                editingHost = it
                                hostEditorVisible = true
                            },
                            onDelete = { deletingHost = it },
                            onConnect = { host ->
                                if (state.connectionState(host.id).connectionPhase ==
                                    ConnectionPhase.CONNECTED
                                ) {
                                    controller.selectHost(host.id)
                                } else {
                                    controller.connect(host.id)
                                }
                                section = MainSection.SESSIONS
                            },
                            onOpenTerminal = controller::openTerminal,
                        )
                        MainSection.SESSIONS -> SessionsScreen(
                            state = state,
                            controller = controller,
                            onNewThread = { path -> newThreadProject = path },
                            onOpenHosts = { section = MainSection.HOSTS },
                        )
                        MainSection.SETTINGS -> SettingsScreen(
                            state = state,
                            controller = controller,
                            onOpenIdentities = { section = MainSection.IDENTITIES },
                            onOpenDiagnostics = { section = MainSection.DIAGNOSTICS },
                        )
                        MainSection.IDENTITIES -> IdentitiesScreen(
                            state = state,
                            controller = controller,
                            onBack = { section = MainSection.SETTINGS },
                            onImport = { showImportIdentity = true },
                            onGenerate = { showGenerateIdentity = true },
                            onDelete = { deletingIdentityId = it },
                        )
                        MainSection.DIAGNOSTICS -> DiagnosticLogScreen(
                            state = state,
                            controller = controller,
                            onBack = { section = MainSection.SETTINGS },
                        )
                    }
                }
            }
        }
    }

    if (hostEditorVisible) {
        HostEditorDialog(
            host = editingHost,
            hosts = state.hosts,
            identities = state.identities,
            onDismiss = { hostEditorVisible = false },
            onSave = { draft, password, replacePassword, appServerToken, replaceToken ->
                controller.saveHost(
                    draft,
                    password,
                    replacePassword,
                    appServerToken,
                    replaceToken,
                ) { saved ->
                    if (saved) hostEditorVisible = false
                }
            },
        )
    }
    if (showImportIdentity) {
        ImportIdentityDialog(
            busy = state.busy,
            onDismiss = { showImportIdentity = false },
            onImport = { label, pem, passphrase ->
                controller.importIdentity(label, pem, passphrase) { done ->
                    if (done) showImportIdentity = false
                }
            },
        )
    }
    if (showGenerateIdentity) {
        GenerateIdentityDialog(
            onDismiss = { showGenerateIdentity = false },
            onGenerate = { label, type, passphrase ->
                controller.generateIdentity(label, type, passphrase) { done ->
                    if (done) showGenerateIdentity = false
                }
            },
        )
    }
    deletingHost?.let { host ->
        ConfirmDialog(
            title = "删除服务器？",
            message = "${host.label}\n本机保存的 Host Key 信任记录不会随之导出。",
            onDismiss = { deletingHost = null },
            onConfirm = {
                controller.deleteHost(host.id)
                deletingHost = null
            },
        )
    }
    deletingIdentityId?.let { id ->
        ConfirmDialog(
            title = "删除 SSH 身份？",
            message = "如果仍有 Host 引用此身份，删除会被阻止。",
            onDismiss = { deletingIdentityId = null },
            onConfirm = {
                controller.deleteIdentity(id)
                deletingIdentityId = null
            },
        )
    }
    newThreadProject?.let { path ->
        NewThreadDialog(
            initialPath = path,
            projects = groupThreads(state.threads).map { it.path }.filter(String::isNotBlank),
            onDismiss = { newThreadProject = null },
            onCreate = {
                controller.startThread(it)
                newThreadProject = null
            },
        )
    }

    ConnectionPromptDialogs(controller = controller, state = state)
    state.approvals.firstOrNull()?.let { approval ->
        ApprovalDialog(
            request = approval,
            controller = controller,
            responding = approval.approvalKey() in state.respondingApprovalIds,
        )
    }
    state.publicKeyDialog?.let { publicKey ->
        PublicKeyDialog(publicKey, onDismiss = controller::dismissPublicKey)
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = controller::clearError,
            title = { Text("操作未完成") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = controller::clearError) { Text("确定") }
            },
        )
    }
}

private enum class MainNavigation(val section: MainSection, val label: String) {
    HOSTS(MainSection.HOSTS, "服务器"),
    SESSIONS(MainSection.SESSIONS, "Codex"),
    SETTINGS(MainSection.SETTINGS, "设置");

    fun icon() = when (this) {
        HOSTS -> Icons.Default.Home
        SESSIONS -> Icons.Default.Menu
        SETTINGS -> Icons.Default.MoreVert
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
