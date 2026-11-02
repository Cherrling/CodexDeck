package cc.cherr.codexdeck

import cc.cherr.codexdeck.data.AppSettings
import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.data.IdentityRecord
import cc.cherr.codexdeck.protocol.ApprovalRequest
import cc.cherr.codexdeck.protocol.CollaborationMode
import cc.cherr.codexdeck.protocol.CodexThread
import cc.cherr.codexdeck.protocol.ModelInfo
import cc.cherr.codexdeck.protocol.PermissionProfile
import cc.cherr.codexdeck.protocol.ThreadSession
import cc.cherr.codexdeck.protocol.ThreadTurn
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.terminal.ShellTerminalController

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class AppState(
    val hosts: List<HostRecord> = emptyList(),
    val identities: List<IdentityRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionMessage: String = "尚未连接",
    val activeHostId: String? = null,
    val threads: List<CodexThread> = emptyList(),
    val threadSearch: String = "",
    val selectedThreadId: String? = null,
    val selectedSession: ThreadSession? = null,
    val turns: List<ThreadTurn> = emptyList(),
    val olderHistoryCursor: String? = null,
    val isLoadingThread: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val activeTurnId: String? = null,
    val isTurnRunning: Boolean = false,
    val isSending: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val selectedReasoningEffort: String? = null,
    val collaborationModes: List<CollaborationMode> = emptyList(),
    val selectedCollaborationMode: String = "default",
    val permissionProfiles: List<PermissionProfile> = emptyList(),
    val selectedPermissionProfileId: String? = null,
    val approvalPolicy: String = "on-request",
    val sandbox: String = "workspace-write",
    val pendingPrompt: ConnectionPrompt? = null,
    val approvals: List<ApprovalRequest> = emptyList(),
    val composerEpoch: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastDiagnostic: String? = null,
    val terminalSessions: List<TerminalSessionHandle> = emptyList(),
    val selectedTerminalId: String? = null,
    val publicKeyDialog: PublicKeyDialogData? = null,
) {
    val activeHost: HostRecord?
        get() = hosts.firstOrNull { it.id == activeHostId }

    val selectedThread: CodexThread?
        get() = threads.firstOrNull { it.id == selectedThreadId }

    val selectedModel: ModelInfo?
        get() = models.firstOrNull { it.id == selectedModelId }

    val visibleThreads: List<CodexThread>
        get() {
            val query = threadSearch.trim()
            if (query.isEmpty()) return threads
            return threads.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.cwd.contains(query, ignoreCase = true) ||
                    it.preview.contains(query, ignoreCase = true)
            }
        }
}

data class PublicKeyDialogData(
    val label: String,
    val algorithm: String,
    val fingerprint: String,
    val publicKey: String,
)

data class TerminalSessionHandle(
    val id: String,
    val hostId: String,
    val hostLabel: String,
    val controller: ShellTerminalController,
)

data class ThreadProject(
    val path: String,
    val name: String,
    val threads: List<CodexThread>,
)

fun groupThreads(threads: List<CodexThread>): List<ThreadProject> =
    threads.groupBy { it.cwd.trim().ifBlank { "未识别工作目录" } }
        .map { (path, items) ->
            ThreadProject(
                path = path,
                name = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                threads = items.sortedByDescending { it.updatedAt },
            )
        }
        .sortedByDescending { project -> project.threads.maxOfOrNull { it.updatedAt } ?: 0 }
