package cc.cherr.codexdeck

import android.content.Context
import androidx.room.Room
import cc.cherr.codexdeck.data.AppSettings
import cc.cherr.codexdeck.data.CodexDeckDatabase
import cc.cherr.codexdeck.data.CredentialVault
import cc.cherr.codexdeck.data.HostDraft
import cc.cherr.codexdeck.data.IdentityGeneration
import cc.cherr.codexdeck.data.LocalRepository
import cc.cherr.codexdeck.data.SettingsStore
import cc.cherr.codexdeck.protocol.AppServerClient
import cc.cherr.codexdeck.protocol.ApprovalKind
import cc.cherr.codexdeck.protocol.ApprovalRequest
import cc.cherr.codexdeck.protocol.ClientInfo
import cc.cherr.codexdeck.protocol.CodexEvent
import cc.cherr.codexdeck.protocol.CodexThread
import cc.cherr.codexdeck.protocol.ThreadItem
import cc.cherr.codexdeck.protocol.ThreadStartOptions
import cc.cherr.codexdeck.protocol.ThreadTurn
import cc.cherr.codexdeck.protocol.TurnOptions
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.ssh.ConnectionPromptGateway
import cc.cherr.codexdeck.ssh.SshAuthenticationCancelled
import cc.cherr.codexdeck.ssh.SshConnectionManager
import cc.cherr.codexdeck.ssh.TrustDecision
import cc.cherr.codexdeck.terminal.ShellTerminalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.Security
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AppController(
    context: Context,
    dao: cc.cherr.codexdeck.data.StoreDao,
    private val vault: CredentialVault,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore = SettingsStore(appContext)
    private val local = LocalRepository(dao, vault)
    private val ssh = SshConnectionManager(dao, vault)
    private val prompts = ConnectionPromptGateway()
    private val actionMutex = Mutex()
    private val generation = AtomicInteger()
    private val _state = MutableStateFlow(AppState(settings = settingsStore.read()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var appServer: AppServerClient? = null
    private var appServerTransport: cc.cherr.codexdeck.ssh.SshJsonLineTransport? = null
    private var eventJob: Job? = null
    private var diagnosticJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var threadRefreshJob: Job? = null
    private var userDisconnected = true

    init {
        scope.launch {
            prompts.prompts.collect { prompt ->
                _state.update { it.copy(pendingPrompt = prompt) }
            }
        }
        refreshLocal()
    }

    fun connect(hostId: String) {
        if (connectJob?.isActive == true || reconnectJob?.isActive == true) return
        userDisconnected = false
        reconnectJob?.cancel()
        connectJob = scope.launch {
            connectInternal(hostId = hostId, reconnect = false, reopenThreadId = null)
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        connectJob?.cancel()
        closeCodexConnection()
        _state.value.terminalSessions.forEach { it.controller.close() }
        ssh.close(state.value.activeHostId.orEmpty())
        prompts.cancelAll()
        _state.update {
            it.copy(
                connectionPhase = ConnectionPhase.DISCONNECTED,
                connectionMessage = "已断开",
                activeHostId = null,
                selectedThreadId = null,
                selectedSession = null,
                turns = emptyList(),
                activeTurnId = null,
                isTurnRunning = false,
                pendingPrompt = null,
                approvals = emptyList(),
                terminalSessions = emptyList(),
                selectedTerminalId = null,
            )
        }
    }

    fun reconnect() {
        state.value.activeHostId?.let(::connect)
    }

    fun openTerminal(hostId: String) {
        val host = state.value.hosts.firstOrNull { it.id == hostId } ?: return showError("服务器不存在")
        scope.launch {
            val message = if (state.value.connectionPhase == ConnectionPhase.CONNECTED &&
                state.value.activeHostId == hostId
            ) {
                "正在打开 Shell…"
            } else {
                _state.update {
                    it.copy(
                        connectionPhase = ConnectionPhase.CONNECTING,
                        connectionMessage = "正在为 ${host.label} 建立 SSH 连接…",
                        error = null,
                    )
                }
                "正在建立 SSH 连接…"
            }
            _state.update { it.copy(notice = message) }
            try {
                val connection = ssh.connect(hostId, prompts)
                val id = UUID.randomUUID().toString()
                var controller: ShellTerminalController? = null
                controller = ShellTerminalController(
                    context = appContext,
                    transport = connection.newShellTransport(),
                    onFinished = {
                        controller?.let { removeTerminal(id, closeController = false) }
                    },
                )
                val handle = TerminalSessionHandle(
                    id = id,
                    hostId = hostId,
                    hostLabel = host.label,
                    controller = controller,
                )
                _state.update {
                    it.copy(
                        terminalSessions = it.terminalSessions + handle,
                        selectedTerminalId = id,
                        notice = null,
                        connectionPhase = if (it.connectionPhase == ConnectionPhase.ERROR) {
                            ConnectionPhase.CONNECTED
                        } else {
                            it.connectionPhase
                        },
                    )
                }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        connectionPhase = ConnectionPhase.ERROR,
                        connectionMessage = friendlyError(failure),
                        error = friendlyError(failure),
                        notice = null,
                    )
                }
            }
        }
    }

    fun selectTerminal(id: String?) = _state.update { it.copy(selectedTerminalId = id) }

    fun closeTerminal(id: String) {
        removeTerminal(id, closeController = true)
    }

    private fun removeTerminal(id: String, closeController: Boolean) {
        val handle = state.value.terminalSessions.firstOrNull { it.id == id }
        if (closeController) handle?.controller?.close()
        _state.update { current ->
            val remaining = current.terminalSessions.filterNot { it.id == id }
            current.copy(
                terminalSessions = remaining,
                selectedTerminalId = if (current.selectedTerminalId == id) {
                    remaining.lastOrNull()?.id
                } else {
                    current.selectedTerminalId
                },
            )
        }
    }

    private suspend fun connectInternal(
        hostId: String,
        reconnect: Boolean,
        reopenThreadId: String?,
    ) {
        val host = local.host(hostId) ?: run {
            showError("服务器不存在")
            return
        }
        closeCodexConnection()
        ssh.close(hostId)
        prompts.cancelAll()
        val generationAtStart = generation.incrementAndGet()
        _state.update {
            it.copy(
                connectionPhase = if (reconnect) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
                connectionMessage = if (reconnect) "网络已恢复，正在重新同步 ${host.label}…" else "正在连接 ${host.label}…",
                activeHostId = hostId,
                error = null,
                pendingPrompt = null,
            )
        }
        try {
            val sshConnection = ssh.connect(hostId, prompts)
            if (generationAtStart != generation.get()) return
            val transport = sshConnection.openAppServer(host.codexExecutable)
            appServerTransport = transport
            diagnosticJob = scope.launch {
                transport.diagnostics.collect { line ->
                    if (generationAtStart == generation.get()) {
                        _state.update { it.copy(lastDiagnostic = line.take(2_000)) }
                    }
                }
            }
            val client = AppServerClient(
                transport = transport,
                clientInfo = ClientInfo(
                    version = BuildConfig.VERSION_NAME,
                ),
            )
            appServer = client
            eventJob = scope.launch {
                client.events.collect { event ->
                    if (client === appServer) handleEvent(event)
                }
            }
            client.initialize()
            if (generationAtStart != generation.get()) return

            val threads = client.listThreads()
            val models = runCatching { client.listModels() }.getOrDefault(emptyList())
            val modes = runCatching { client.listCollaborationModes() }.getOrDefault(emptyList())
            val profiles = runCatching { client.listPermissionProfiles() }.getOrDefault(emptyList())
            local.cacheThreads(hostId, threads)
            local.markUsed(hostId)
            val selectedModel = models.firstOrNull { it.isDefault } ?: models.firstOrNull()
            val projects = groupThreads(threads)
            _state.update {
                it.copy(
                    connectionPhase = ConnectionPhase.CONNECTED,
                    connectionMessage = buildString {
                        append("${projects.size} 个项目 · ${threads.size} 个会话")
                        append(" · Codex ")
                        append(BuildConfig.VERSION_NAME)
                    },
                    threads = threads,
                    models = models,
                    selectedModelId = it.selectedModelId?.takeIf { id -> models.any { model -> model.id == id } }
                        ?: selectedModel?.id,
                    selectedReasoningEffort = it.selectedReasoningEffort
                        ?: selectedModel?.defaultReasoningEffort,
                    collaborationModes = modes,
                    selectedCollaborationMode = it.selectedCollaborationMode.takeIf { requested ->
                        modes.any { mode -> mode.mode == requested }
                    } ?: modes.firstOrNull { mode -> mode.mode == "default" }?.mode ?: "default",
                    permissionProfiles = profiles,
                    pendingPrompt = null,
                    error = null,
                )
            }
            refreshHosts()
            if (reopenThreadId != null) openThread(reopenThreadId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (generationAtStart != generation.get()) return
            closeCodexConnection()
            val message = friendlyError(failure)
            _state.update {
                it.copy(
                    connectionPhase = ConnectionPhase.ERROR,
                    connectionMessage = message,
                    error = message,
                    pendingPrompt = null,
                )
            }
            if (!reconnect && !userDisconnected) scheduleReconnect(hostId)
        }
    }

    private fun handleEvent(event: CodexEvent) {
        when (event) {
            is CodexEvent.ItemUpsert -> _state.update { state ->
                state.copy(turns = upsertItem(state.turns, event.item, state.activeTurnId))
            }
            is CodexEvent.AgentDelta -> appendDelta(event.itemId, "text", event.delta)
            is CodexEvent.PlanDelta -> appendDelta(event.itemId, "text", event.delta)
            is CodexEvent.ReasoningDelta -> appendDelta(event.itemId, "text", event.delta)
            is CodexEvent.CommandOutputDelta -> appendDelta(event.itemId, "aggregatedOutput", event.delta)
            is CodexEvent.TurnChanged -> _state.update {
                it.copy(
                    activeTurnId = event.turnId ?: it.activeTurnId,
                    isTurnRunning = event.running,
                    error = event.error ?: it.error,
                )
            }
            is CodexEvent.ThreadChanged -> scheduleThreadRefresh()
            is CodexEvent.ApprovalRequested -> _state.update { current ->
                val id = event.request.requestId.wireValue
                current.copy(approvals = current.approvals.filterNot { it.requestId.wireValue == id } + event.request)
            }
            is CodexEvent.Warning -> _state.update { it.copy(notice = event.message) }
            is CodexEvent.ServerError -> _state.update {
                it.copy(error = event.message, isTurnRunning = false)
            }
            is CodexEvent.Diagnostic -> _state.update {
                it.copy(lastDiagnostic = event.message.take(2_000))
            }
            is CodexEvent.Disconnected -> {
                if (!userDisconnected && event.cause != null) scheduleReconnect(state.value.activeHostId)
            }
            is CodexEvent.Initialized -> Unit
        }
    }

    private fun appendDelta(itemId: String, field: String, delta: String) {
        if (itemId.isBlank() || delta.isEmpty()) return
        _state.update { state ->
            val existingTurnIndex = state.turns.indexOfFirst { turn -> turn.items.any { it.id == itemId } }
            if (existingTurnIndex >= 0) {
                val turns = state.turns.toMutableList()
                val turn = turns[existingTurnIndex]
                turns[existingTurnIndex] = turn.copy(
                    items = turn.items.map { item ->
                        if (item.id != itemId) item else {
                            val previous = item.raw[field]?.let {
                                runCatching { (it as JsonPrimitive).content }.getOrNull()
                            }.orEmpty()
                            item.copy(raw = JsonObject(item.raw + (field to JsonPrimitive(previous + delta))))
                        }
                    },
                )
                state.copy(turns = turns)
            } else {
                val transient = ThreadItem(
                    id = itemId,
                    type = when (field) {
                        "aggregatedOutput" -> "commandExecution"
                        else -> "agentMessage"
                    },
                    status = "inProgress",
                    raw = buildJsonObject { put(field, delta) },
                )
                state.copy(turns = upsertItem(state.turns, transient, state.activeTurnId))
            }
        }
    }

    fun openThread(threadId: String) {
        val client = appServer ?: return showError("请先连接服务器")
        scope.launch {
            _state.update { it.copy(isLoadingThread = true, selectedThreadId = threadId, error = null) }
            try {
                val session = client.resumeThread(
                    threadId = threadId,
                    approvalPolicy = state.value.approvalPolicy,
                    approvalsReviewer = "user",
                    sandbox = state.value.sandbox,
                    permissionProfile = state.value.selectedPermissionProfileId,
                )
                val page = client.listTurns(threadId, limit = 20, sortDirection = "desc")
                val chronological = page.turns.asReversed()
                val activeTurn = page.turns.firstOrNull { it.status == "inProgress" }
                _state.update {
                    it.copy(
                        selectedThreadId = threadId,
                        selectedSession = session,
                        turns = mergeTurns(session.turns, chronological),
                        olderHistoryCursor = page.nextCursor,
                        activeTurnId = activeTurn?.id,
                        isTurnRunning = activeTurn != null,
                        isLoadingThread = false,
                        selectedModelId = session.model ?: it.selectedModelId,
                        selectedReasoningEffort = session.reasoningEffort ?: it.selectedReasoningEffort,
                        selectedCollaborationMode = session.collaborationMode ?: "default",
                    )
                }
            } catch (failure: Throwable) {
                _state.update { it.copy(isLoadingThread = false, error = friendlyError(failure)) }
            }
        }
    }

    fun closeThread() {
        _state.update {
            it.copy(
                selectedThreadId = null,
                selectedSession = null,
                turns = emptyList(),
                activeTurnId = null,
                isTurnRunning = false,
                olderHistoryCursor = null,
                approvals = emptyList(),
            )
        }
    }

    fun loadOlderHistory() {
        val client = appServer ?: return
        val stateBefore = state.value
        val threadId = stateBefore.selectedThreadId ?: return
        val cursor = stateBefore.olderHistoryCursor ?: return
        if (stateBefore.isLoadingHistory) return
        scope.launch {
            _state.update { it.copy(isLoadingHistory = true) }
            try {
                val page = client.listTurns(threadId, cursor = cursor, limit = 20, sortDirection = "desc")
                _state.update {
                    it.copy(
                        turns = mergeTurns(page.turns.asReversed(), it.turns),
                        olderHistoryCursor = page.nextCursor,
                        isLoadingHistory = false,
                    )
                }
            } catch (failure: Throwable) {
                _state.update { it.copy(isLoadingHistory = false, error = friendlyError(failure)) }
            }
        }
    }

    fun startThread(cwd: String) {
        val client = appServer ?: return showError("请先连接服务器")
        val path = cwd.trim()
        if (!path.startsWith('/')) return showError("项目路径必须是以 / 开头的远程绝对路径")
        scope.launch {
            actionMutex.withLock {
                _state.update { it.copy(busy = true, error = null) }
                try {
                    val session = client.startThread(
                        ThreadStartOptions(
                            cwd = path,
                            model = state.value.selectedModelId,
                            approvalPolicy = state.value.approvalPolicy,
                            approvalsReviewer = "user",
                            sandbox = state.value.sandbox,
                            permissionProfile = state.value.selectedPermissionProfileId,
                        ),
                    )
                    _state.update {
                        it.copy(
                            threads = (it.threads + session.thread).distinctBy(CodexThread::id),
                            selectedThreadId = session.thread.id,
                            selectedSession = session,
                            turns = emptyList(),
                            activeTurnId = null,
                            isTurnRunning = false,
                            olderHistoryCursor = null,
                            busy = false,
                        )
                    }
                } catch (failure: Throwable) {
                    _state.update { it.copy(busy = false, error = friendlyError(failure)) }
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val current = state.value
        if (current.isSending) return
        val client = appServer ?: return showError("请先连接服务器")
        val threadId = current.selectedThreadId ?: return showError("请先选择会话")
        val session = current.selectedSession ?: return showError("会话尚未恢复")
        scope.launch {
            _state.update { it.copy(isSending = true, error = null) }
            try {
                if (current.isTurnRunning && !current.activeTurnId.isNullOrBlank()) {
                    client.steerTurn(threadId, current.activeTurnId!!, body)
                } else {
                    val turn = client.startTurn(
                        threadId = threadId,
                        text = body,
                        options = TurnOptions(
                            cwd = session.cwd,
                            model = current.selectedModelId,
                            reasoningEffort = current.selectedReasoningEffort,
                            approvalPolicy = current.approvalPolicy,
                            approvalsReviewer = "user",
                            sandbox = current.sandbox,
                            permissionProfile = current.selectedPermissionProfileId,
                            collaborationMode = collaborationModePayload(current),
                        ),
                    )
                    _state.update {
                        it.copy(
                            turns = upsertTurn(it.turns, turn),
                            activeTurnId = turn.id,
                            isTurnRunning = true,
                        )
                    }
                }
                _state.update { it.copy(isSending = false, composerEpoch = it.composerEpoch + 1) }
            } catch (failure: Throwable) {
                _state.update { it.copy(isSending = false, error = friendlyError(failure)) }
            }
        }
    }

    fun interruptTurn() {
        val client = appServer ?: return
        val state = state.value
        val threadId = state.selectedThreadId ?: return
        val turnId = state.activeTurnId ?: return
        scope.launch {
            runCatching { client.interruptTurn(threadId, turnId) }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun compactThread() {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.compactThread(threadId) }
                .onSuccess { _state.update { it.copy(notice = "已请求压缩会话上下文") } }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun forkThread() {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.forkThread(threadId) }
                .onSuccess { session ->
                    _state.update {
                        it.copy(
                            threads = (it.threads + session.thread).distinctBy(CodexThread::id),
                            selectedThreadId = session.thread.id,
                            selectedSession = session,
                            turns = emptyList(),
                            activeTurnId = null,
                            isTurnRunning = false,
                        )
                    }
                }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun archiveSelectedThread() {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.archiveThread(threadId) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            threads = it.threads.filterNot { thread -> thread.id == threadId },
                            selectedThreadId = null,
                            selectedSession = null,
                            turns = emptyList(),
                            notice = "会话已归档",
                        )
                    }
                }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun renameSelectedThread(name: String) {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        if (name.isBlank()) return
        scope.launch {
            runCatching { client.renameThread(threadId, name.trim()) }
                .onSuccess { refreshThreadsNow() }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun respondToApproval(
        request: ApprovalRequest,
        decision: String,
        answers: Map<String, List<String>> = emptyMap(),
    ) {
        val client = appServer ?: return
        scope.launch {
            try {
                client.approve(request, decision, answers)
                removeApproval(request)
            } catch (failure: Throwable) {
                showError(friendlyError(failure))
            }
        }
    }

    fun rejectApproval(request: ApprovalRequest) {
        val client = appServer ?: return
        scope.launch {
            try {
                if (request.kind == ApprovalKind.UNKNOWN) {
                    client.rejectServerRequest(request, "Unsupported server request")
                } else {
                    client.approve(request, decision = "decline")
                }
                removeApproval(request)
            } catch (failure: Throwable) {
                showError(friendlyError(failure))
            }
        }
    }

    private fun removeApproval(request: ApprovalRequest) {
        _state.update { state ->
            state.copy(
                approvals = state.approvals.filterNot {
                    it.requestId.wireValue == request.requestId.wireValue
                },
            )
        }
    }

    fun submitPromptSecret(prompt: ConnectionPrompt.Secret, secret: String) {
        _state.update { it.copy(pendingPrompt = null) }
        prompts.submitSecret(prompt.id, secret)
    }

    fun submitHostTrust(prompt: ConnectionPrompt.HostKey, decision: TrustDecision) {
        _state.update { it.copy(pendingPrompt = null) }
        prompts.submitTrust(prompt.id, decision)
    }

    fun saveHost(draft: HostDraft, password: String, replacePassword: Boolean, done: (Boolean) -> Unit = {}) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                local.saveHost(
                    draft = draft,
                    password = password.takeIf { replacePassword }?.toCharArray(),
                    replacePassword = replacePassword,
                )
                refreshHosts()
                _state.update { it.copy(busy = false, notice = "服务器已保存") }
                done(true)
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = friendlyError(failure)) }
                done(false)
            }
        }
    }

    fun duplicateHost(hostId: String) {
        val host = state.value.hosts.firstOrNull { it.id == hostId } ?: return
        saveHost(
            draft = HostDraft.from(host).copy(
                id = null,
                label = "${host.label}（副本）",
                favorite = false,
            ),
            password = "",
            replacePassword = false,
        )
    }

    fun deleteHost(hostId: String) {
        scope.launch {
            try {
                if (state.value.activeHostId == hostId) disconnect()
                local.deleteHost(hostId)
                refreshHosts()
                _state.update { it.copy(notice = "服务器已删除") }
            } catch (failure: Throwable) {
                showError(friendlyError(failure))
            }
        }
    }

    fun toggleFavorite(hostId: String) {
        scope.launch {
            runCatching { local.toggleFavorite(hostId) }
                .onSuccess { refreshHosts() }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun importIdentity(label: String, pem: ByteArray, passphrase: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                local.importIdentity(label, pem, passphrase.toCharArray())
                refreshIdentities()
                _state.update { it.copy(busy = false, notice = "SSH 私钥已导入") }
                done(true)
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = friendlyError(failure)) }
                done(false)
            } finally {
                pem.fill(0)
            }
        }
    }

    fun generateIdentity(
        label: String,
        generation: IdentityGeneration,
        passphrase: String,
        done: (Boolean) -> Unit = {},
    ) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                local.generateIdentity(label, generation, passphrase.toCharArray())
                refreshIdentities()
                _state.update { it.copy(busy = false, notice = "SSH 身份已生成") }
                done(true)
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = friendlyError(failure)) }
                done(false)
            }
        }
    }

    fun deleteIdentity(id: String) {
        scope.launch {
            try {
                local.deleteIdentity(id)
                refreshIdentities()
                _state.update { it.copy(notice = "SSH 身份已删除") }
            } catch (failure: Throwable) {
                showError(friendlyError(failure))
            }
        }
    }

    fun showPublicKey(id: String) {
        val identity = state.value.identities.firstOrNull { it.id == id } ?: return
        scope.launch {
            try {
                val key = local.publicKey(id)
                _state.update {
                    it.copy(
                        publicKeyDialog = PublicKeyDialogData(
                            label = identity.label,
                            algorithm = identity.algorithm,
                            fingerprint = identity.fingerprint,
                            publicKey = key,
                        ),
                    )
                }
            } catch (failure: Throwable) {
                showError(friendlyError(failure))
            }
        }
    }

    fun dismissPublicKey() = _state.update { it.copy(publicKeyDialog = null) }

    fun updateSettings(settings: AppSettings) {
        settingsStore.write(settings)
        _state.update { it.copy(settings = settings) }
    }

    fun setThreadSearch(value: String) = _state.update { it.copy(threadSearch = value) }
    fun setModel(id: String) {
        val model = state.value.models.firstOrNull { it.id == id }
        _state.update {
            it.copy(
                selectedModelId = id,
                selectedReasoningEffort = model?.defaultReasoningEffort,
            )
        }
    }
    fun setReasoningEffort(value: String) = _state.update { it.copy(selectedReasoningEffort = value) }
    fun setCollaborationMode(value: String) = _state.update { it.copy(selectedCollaborationMode = value) }
    fun setApprovalPolicy(value: String) = _state.update { it.copy(approvalPolicy = value) }
    fun setSandbox(value: String) = _state.update { it.copy(sandbox = value) }
    fun setPermissionProfile(value: String?) = _state.update { it.copy(selectedPermissionProfileId = value) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun refreshThreadsNow() {
        val client = appServer ?: return
        scope.launch {
            val threads = runCatching { client.listThreads() }.getOrNull() ?: return@launch
            _state.value.activeHostId?.let { local.cacheThreads(it, threads) }
            _state.update { state ->
                state.copy(
                    threads = threads,
                    selectedSession = state.selectedSession?.let { session ->
                        threads.firstOrNull { it.id == session.thread.id }?.let {
                            session.copy(thread = it)
                        } ?: session
                    },
                )
            }
        }
    }

    private fun scheduleThreadRefresh() {
        threadRefreshJob?.cancel()
        threadRefreshJob = scope.launch {
            delay(350)
            refreshThreadsNow()
        }
    }

    private fun scheduleReconnect(hostId: String?) {
        if (hostId.isNullOrBlank() || userDisconnected || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val selectedThread = state.value.selectedThreadId
            val delays = longArrayOf(2_000, 5_000, 10_000)
            for ((index, waitMs) in delays.withIndex()) {
                _state.update {
                    it.copy(
                        connectionPhase = ConnectionPhase.RECONNECTING,
                        connectionMessage = "连接中断，${waitMs / 1000} 秒后重试（${index + 1}/${delays.size}）",
                    )
                }
                delay(waitMs)
                try {
                    connectInternal(hostId, reconnect = true, reopenThreadId = selectedThread)
                    if (state.value.connectionPhase == ConnectionPhase.CONNECTED) return@launch
                } catch (_: Throwable) {
                    // connectInternal records the user-facing error.
                }
            }
            _state.update {
                it.copy(
                    connectionPhase = ConnectionPhase.ERROR,
                    connectionMessage = "无法恢复连接；Agent 的实际状态需要在服务器上确认",
                )
            }
        }
    }

    private fun closeCodexConnection() {
        generation.incrementAndGet()
        eventJob?.cancel()
        eventJob = null
        diagnosticJob?.cancel()
        diagnosticJob = null
        appServer?.close()
        appServer = null
        appServerTransport = null
    }

    private fun refreshLocal() {
        scope.launch {
            try {
                val hosts = local.hosts()
                val identities = local.identities()
                _state.update { it.copy(hosts = hosts, identities = identities) }
            } catch (failure: Throwable) {
                showError("读取本地数据失败：${failure.message.orEmpty()}")
            }
        }
    }

    private suspend fun refreshHosts() {
        val hosts = local.hosts()
        _state.update { it.copy(hosts = hosts) }
    }

    private suspend fun refreshIdentities() {
        val identities = local.identities()
        _state.update { it.copy(identities = identities) }
    }

    private fun showError(message: String) {
        _state.update { it.copy(error = message, busy = false, isSending = false) }
    }

    private fun collaborationModePayload(state: AppState): JsonObject? {
        val mode = state.collaborationModes.firstOrNull {
            it.mode == state.selectedCollaborationMode
        } ?: return null
        if (mode.mode == "default") return null
        return buildJsonObject {
            put("mode", mode.mode)
            put("settings", buildJsonObject {
                put("model", mode.model ?: state.selectedModelId ?: "")
                val effort = mode.reasoningEffort ?: state.selectedReasoningEffort
                if (effort == null) put("reasoning_effort", JsonNull)
                else put("reasoning_effort", JsonPrimitive(effort))
                put("developer_instructions", JsonNull)
            })
        }
    }

    private fun friendlyError(failure: Throwable): String {
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is SshAuthenticationCancelled -> return current.message ?: "已取消认证"
                is UnknownHostException -> return "无法解析服务器地址"
                is SocketTimeoutException -> return "连接超时，请检查网络和防火墙"
                is ConnectException -> return "无法连接 SSH 端口"
                is net.schmizz.sshj.userauth.UserAuthException ->
                    return "SSH 认证失败，请检查用户名、密码或密钥权限"
                is SecurityException -> return current.message ?: "SSH 安全校验失败"
                else -> Unit
            }
            current = current.cause
        }
        val detail = generateSequence(failure) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .lastOrNull()
        return detail ?: "操作失败"
    }

    fun close() {
        userDisconnected = true
        scope.cancel()
        closeCodexConnection()
        _state.value.terminalSessions.forEach { it.controller.close() }
        ssh.close()
        prompts.cancelAll()
    }
}

private fun upsertItem(turns: List<ThreadTurn>, item: ThreadItem, activeTurnId: String?): List<ThreadTurn> {
    val existingTurn = turns.indexOfFirst { turn -> turn.items.any { it.id == item.id } }
    if (existingTurn >= 0) {
        return turns.toMutableList().also { copy ->
            val turn = copy[existingTurn]
            copy[existingTurn] = turn.copy(items = turn.items.map { if (it.id == item.id) item else it })
        }
    }
    val targetIndex = turns.indexOfLast { it.id == activeTurnId }
        .takeIf { it >= 0 }
        ?: turns.lastIndex
    if (targetIndex >= 0) {
        return turns.toMutableList().also { copy ->
            val turn = copy[targetIndex]
            copy[targetIndex] = turn.copy(items = turn.items + item)
        }
    }
    return listOf(
        ThreadTurn(
            id = activeTurnId ?: "live",
            status = "inProgress",
            items = listOf(item),
            startedAt = null,
            completedAt = null,
            durationMs = null,
            error = null,
            raw = JsonObject(emptyMap()),
        ),
    )
}

private fun upsertTurn(turns: List<ThreadTurn>, turn: ThreadTurn): List<ThreadTurn> {
    val index = turns.indexOfFirst { it.id == turn.id }
    return if (index >= 0) {
        turns.toMutableList().also { it[index] = turn }
    } else {
        turns + turn
    }
}

private fun mergeTurns(first: List<ThreadTurn>, second: List<ThreadTurn>): List<ThreadTurn> =
    (first + second).associateBy(ThreadTurn::id).values.sortedBy { it.startedAt ?: Long.MAX_VALUE }
