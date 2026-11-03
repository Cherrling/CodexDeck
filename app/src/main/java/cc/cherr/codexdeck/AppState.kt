package cc.cherr.codexdeck

import cc.cherr.codexdeck.data.AppSettings
import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.data.IdentityRecord
import cc.cherr.codexdeck.protocol.ApprovalRequest
import cc.cherr.codexdeck.protocol.CapabilitySnapshot
import cc.cherr.codexdeck.protocol.CollaborationMode
import cc.cherr.codexdeck.protocol.CodexThread
import cc.cherr.codexdeck.protocol.ModelInfo
import cc.cherr.codexdeck.protocol.PermissionProfile
import cc.cherr.codexdeck.protocol.ThreadSession
import cc.cherr.codexdeck.protocol.ThreadTurn
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.terminal.ShellTerminalController
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val hostConnections: Map<String, HostConnectionState> = emptyMap(),
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionMessage: String = "尚未连接",
    val activeHostId: String? = null,
    val threads: List<CodexThread> = emptyList(),
    val threadNextCursor: String? = null,
    val isLoadingThreads: Boolean = false,
    val showArchivedThreads: Boolean = false,
    val threadSearch: String = "",
    val expandedProjectPaths: Set<String> = emptySet(),
    val selectedThreadId: String? = null,
    val selectedSession: ThreadSession? = null,
    val turns: List<ThreadTurn> = emptyList(),
    val threadViews: Map<String, ThreadViewState> = emptyMap(),
    val olderHistoryCursor: String? = null,
    val olderTurnCursor: String? = null,
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
    val approvalPolicyValue: JsonElement = JsonPrimitive("on-request"),
    val approvalsReviewer: String = "user",
    val sandbox: String = "workspace-write",
    val sandboxPolicyValue: JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("workspaceWrite")),
    ),
    val pendingPrompt: ConnectionPrompt? = null,
    val approvals: List<ApprovalRequest> = emptyList(),
    val respondingApprovalIds: Set<String> = emptySet(),
    val composerEpoch: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastDiagnostic: String? = null,
    val terminalSessions: List<TerminalSessionHandle> = emptyList(),
    val selectedTerminalId: String? = null,
    val publicKeyDialog: PublicKeyDialogData? = null,
    val capabilities: CapabilitySnapshot? = null,
    val isLoadingCapabilities: Boolean = false,
) {
    val activeHost: HostRecord?
        get() = hosts.firstOrNull { it.id == activeHostId }

    val activeHostConnection: HostConnectionState?
        get() = activeHostId?.let(::connectionState)

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

    fun connectionState(hostId: String): HostConnectionState =
        if (activeHostId == hostId) {
            captureActiveHostConnection() ?: HostConnectionState(hostId = hostId)
        } else {
            hostConnections[hostId] ?: HostConnectionState(hostId = hostId)
        }
}

data class HostConnectionState(
    val hostId: String,
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionMessage: String = "尚未连接",
    val threads: List<CodexThread> = emptyList(),
    val threadNextCursor: String? = null,
    val isLoadingThreads: Boolean = false,
    val showArchivedThreads: Boolean = false,
    val threadSearch: String = "",
    val expandedProjectPaths: Set<String> = emptySet(),
    val selectedThreadId: String? = null,
    val selectedSession: ThreadSession? = null,
    val turns: List<ThreadTurn> = emptyList(),
    val threadViews: Map<String, ThreadViewState> = emptyMap(),
    val olderHistoryCursor: String? = null,
    val olderTurnCursor: String? = null,
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
    val approvalPolicyValue: JsonElement = JsonPrimitive("on-request"),
    val approvalsReviewer: String = "user",
    val sandbox: String = "workspace-write",
    val sandboxPolicyValue: JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("workspaceWrite")),
    ),
    val approvals: List<ApprovalRequest> = emptyList(),
    val respondingApprovalIds: Set<String> = emptySet(),
    val composerEpoch: Int = 0,
    val capabilities: CapabilitySnapshot? = null,
    val isLoadingCapabilities: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val lastDiagnostic: String? = null,
) {
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

internal fun AppState.captureActiveHostConnection(): HostConnectionState? {
    val hostId = activeHostId ?: return null
    return HostConnectionState(
        hostId = hostId,
        connectionPhase = connectionPhase,
        connectionMessage = connectionMessage,
        threads = threads,
        threadNextCursor = threadNextCursor,
        isLoadingThreads = isLoadingThreads,
        showArchivedThreads = showArchivedThreads,
        threadSearch = threadSearch,
        expandedProjectPaths = expandedProjectPaths,
        selectedThreadId = selectedThreadId,
        selectedSession = selectedSession,
        turns = turns,
        threadViews = threadViews,
        olderHistoryCursor = olderHistoryCursor,
        olderTurnCursor = olderTurnCursor,
        isLoadingThread = isLoadingThread,
        isLoadingHistory = isLoadingHistory,
        activeTurnId = activeTurnId,
        isTurnRunning = isTurnRunning,
        isSending = isSending,
        models = models,
        selectedModelId = selectedModelId,
        selectedReasoningEffort = selectedReasoningEffort,
        collaborationModes = collaborationModes,
        selectedCollaborationMode = selectedCollaborationMode,
        permissionProfiles = permissionProfiles,
        selectedPermissionProfileId = selectedPermissionProfileId,
        approvalPolicy = approvalPolicy,
        approvalPolicyValue = approvalPolicyValue,
        approvalsReviewer = approvalsReviewer,
        sandbox = sandbox,
        sandboxPolicyValue = sandboxPolicyValue,
        approvals = approvals,
        respondingApprovalIds = respondingApprovalIds,
        composerEpoch = composerEpoch,
        capabilities = capabilities,
        isLoadingCapabilities = isLoadingCapabilities,
        notice = notice,
        error = error,
        lastDiagnostic = lastDiagnostic,
    )
}

internal fun AppState.applyHostConnection(connection: HostConnectionState): AppState = copy(
    activeHostId = connection.hostId,
    connectionPhase = connection.connectionPhase,
    connectionMessage = connection.connectionMessage,
    threads = connection.threads,
    threadNextCursor = connection.threadNextCursor,
    isLoadingThreads = connection.isLoadingThreads,
    showArchivedThreads = connection.showArchivedThreads,
    threadSearch = connection.threadSearch,
    expandedProjectPaths = connection.expandedProjectPaths,
    selectedThreadId = connection.selectedThreadId,
    selectedSession = connection.selectedSession,
    turns = connection.turns,
    threadViews = connection.threadViews,
    olderHistoryCursor = connection.olderHistoryCursor,
    olderTurnCursor = connection.olderTurnCursor,
    isLoadingThread = connection.isLoadingThread,
    isLoadingHistory = connection.isLoadingHistory,
    activeTurnId = connection.activeTurnId,
    isTurnRunning = connection.isTurnRunning,
    isSending = connection.isSending,
    models = connection.models,
    selectedModelId = connection.selectedModelId,
    selectedReasoningEffort = connection.selectedReasoningEffort,
    collaborationModes = connection.collaborationModes,
    selectedCollaborationMode = connection.selectedCollaborationMode,
    permissionProfiles = connection.permissionProfiles,
    selectedPermissionProfileId = connection.selectedPermissionProfileId,
    approvalPolicy = connection.approvalPolicy,
    approvalPolicyValue = connection.approvalPolicyValue,
    approvalsReviewer = connection.approvalsReviewer,
    sandbox = connection.sandbox,
    sandboxPolicyValue = connection.sandboxPolicyValue,
    approvals = connection.approvals,
    respondingApprovalIds = connection.respondingApprovalIds,
    composerEpoch = connection.composerEpoch,
    capabilities = connection.capabilities,
    isLoadingCapabilities = connection.isLoadingCapabilities,
    notice = connection.notice,
    error = connection.error,
    lastDiagnostic = connection.lastDiagnostic,
)

internal fun AppState.activateHost(hostId: String): AppState {
    val states = hostConnections.toMutableMap()
    captureActiveHostConnection()?.let { states[it.hostId] = it }
    val target = states[hostId] ?: HostConnectionState(hostId = hostId)
    return applyHostConnection(target).copy(hostConnections = states)
}

internal fun AppState.withHostConnection(
    hostId: String,
    transform: (HostConnectionState) -> HostConnectionState,
): AppState {
    val current = if (activeHostId == hostId) {
        captureActiveHostConnection() ?: HostConnectionState(hostId = hostId)
    } else {
        hostConnections[hostId] ?: HostConnectionState(hostId = hostId)
    }
    val updated = transform(current)
    return if (activeHostId == hostId) {
        applyHostConnection(updated)
    } else {
        copy(hostConnections = hostConnections + (hostId to updated))
    }
}

internal fun AppState.storeActiveHostConnection(): AppState {
    val active = captureActiveHostConnection() ?: return this
    return copy(hostConnections = hostConnections + (active.hostId to active))
}

data class ThreadViewState(
    val threadId: String,
    val session: ThreadSession? = null,
    val turns: List<ThreadTurn> = emptyList(),
    val olderHistoryCursor: String? = null,
    val olderTurnCursor: String? = null,
    val activeTurnId: String? = null,
    val isTurnRunning: Boolean = false,
    val lastUsedAt: Long = System.currentTimeMillis(),
)

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
    threads.groupBy { normalizeRemoteProjectPath(it.cwd) }
        .map { (path, items) ->
            ThreadProject(
                path = path,
                name = path.trimEnd('/', '\\')
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { path },
                threads = items.sortedByDescending { it.updatedAt },
            )
        }
        .sortedByDescending { project -> project.threads.maxOfOrNull { it.updatedAt } ?: 0 }

internal fun normalizeRemoteProjectPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return "未识别工作目录"
    val drivePath = trimmed.matches(Regex("^[A-Za-z]:[\\\\/].*"))
    val normalized = if (drivePath) trimmed.replace('\\', '/') else trimmed
    val root = when {
        normalized == "/" -> "/"
        normalized.matches(Regex("^[A-Za-z]:/$")) -> normalized
        else -> normalized.trimEnd('/')
    }
    return if (drivePath) root.lowercase() else root
}
