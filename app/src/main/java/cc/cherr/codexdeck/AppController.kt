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
import cc.cherr.codexdeck.protocol.CapabilitySnapshot
import cc.cherr.codexdeck.protocol.JsonLineTransport
import cc.cherr.codexdeck.protocol.RequestId
import cc.cherr.codexdeck.protocol.RpcException
import cc.cherr.codexdeck.protocol.ThreadItem
import cc.cherr.codexdeck.protocol.ThreadSession
import cc.cherr.codexdeck.protocol.ThreadSettingsSnapshot
import cc.cherr.codexdeck.protocol.ThreadStartOptions
import cc.cherr.codexdeck.protocol.ThreadTurn
import cc.cherr.codexdeck.protocol.TurnOptions
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.ssh.ConnectionPromptGateway
import cc.cherr.codexdeck.ssh.SshAuthenticationCancelled
import cc.cherr.codexdeck.ssh.SshConnectionManager
import cc.cherr.codexdeck.ssh.SshJsonLineTransport
import cc.cherr.codexdeck.ssh.SshLocalTunnel
import cc.cherr.codexdeck.ssh.TrustDecision
import cc.cherr.codexdeck.terminal.ShellTerminalController
import cc.cherr.codexdeck.transport.WebSocketJsonLineTransport
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.Security
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private const val DELTA_FLUSH_INTERVAL_MS = 40L
private const val MAX_ITEM_TEXT_CHARS = 512 * 1024
private const val MAX_TURNS_IN_MEMORY = 120
private const val MAX_CACHED_THREAD_VIEWS = 8
private const val MAX_IMAGE_ATTACHMENTS = 4
private const val MAX_IMAGE_DATA_CHARS = 12 * 1024 * 1024
private const val TRUNCATION_MARKER = "\n\n[Earlier output truncated by CodexDeck]\n\n"

class AppController(
    context: Context,
    dao: cc.cherr.codexdeck.data.StoreDao,
    private val vault: CredentialVault,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore = SettingsStore(appContext)
    private val local = LocalRepository(dao, vault)
    private val ssh = SshConnectionManager(dao, vault)
    private val prompts = ConnectionPromptGateway()
    private val controlLock = Any()
    private val generation = AtomicInteger()
    private val _state = MutableStateFlow(AppState(settings = settingsStore.read()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var appServer: AppServerClient? = null
    private var appServerTransport: JsonLineTransport? = null
    private var localTunnel: SshLocalTunnel? = null
    private var eventJob: Job? = null
    private var diagnosticJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var threadRefreshJob: Job? = null
    private var threadSearchJob: Job? = null
    private var threadListJob: Job? = null
    private var userDisconnected = true
    private var restoreAttempted = false

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
        settingsStore.rememberConnection(hostId, threadId = null)
        reconnectJob?.cancel()
        connectJob = scope.launch {
            connectInternal(hostId = hostId, reconnect = false, reopenThreadId = null)
        }
    }

    fun disconnect() {
        userDisconnected = true
        settingsStore.clearConnection()
        reconnectJob?.cancel()
        connectJob?.cancel()
        closeCodexConnection()
        val terminals = _state.value.terminalSessions
        val sshConnections = ssh.detachAllConnections()
        cleanupScope.launch {
            terminals.forEach { runCatching { it.controller.close() } }
            sshConnections.forEach { runCatching { it.close() } }
        }
        prompts.cancelAll()
        ConnectionForegroundService.stop(appContext)
        _state.update {
            it.copy(
                connectionPhase = ConnectionPhase.DISCONNECTED,
                connectionMessage = "已断开",
                activeHostId = null,
                selectedThreadId = null,
                selectedSession = null,
                turns = emptyList(),
                threadViews = emptyMap(),
                threadNextCursor = null,
                isLoadingThreads = false,
                activeTurnId = null,
                isTurnRunning = false,
                pendingPrompt = null,
                approvals = emptyList(),
                respondingApprovalIds = emptySet(),
                terminalSessions = emptyList(),
                selectedTerminalId = null,
                busy = false,
                isSending = false,
                isLoadingThread = false,
                isLoadingHistory = false,
                isLoadingCapabilities = false,
                capabilities = null,
            )
        }
    }

    fun reconnect() {
        state.value.activeHostId?.let(::connect)
    }

    fun restoreLastConnection() {
        if (restoreAttempted) return
        restoreAttempted = true
        val hostId = settingsStore.lastHostId() ?: return
        val threadId = settingsStore.lastThreadId()
        scope.launch {
            if (state.value.activeHostId != null || connectJob?.isActive == true) return@launch
            if (local.host(hostId) == null) {
                settingsStore.clearConnection()
                return@launch
            }
            userDisconnected = false
            connectInternal(hostId, reconnect = true, reopenThreadId = threadId)
        }
    }

    fun openTerminal(hostId: String) {
        val host = state.value.hosts.firstOrNull { it.id == hostId } ?: return showError("服务器不存在")
        scope.launch {
            _state.update { it.copy(notice = "正在为 ${host.label} 建立 SSH Shell…", error = null) }
            try {
                val connection = ssh.connect(hostId, prompts)
                val id = UUID.randomUUID().toString()
                var controller: ShellTerminalController? = null
                controller = ShellTerminalController(
                    context = appContext,
                    transport = connection.newShellTransport(),
                    initialFontSizeSp = state.value.settings.terminalFontSizeSp,
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
                userDisconnected = false
                _state.update {
                    it.copy(
                        terminalSessions = it.terminalSessions + handle,
                        selectedTerminalId = id,
                        notice = null,
                    )
                }
                syncForegroundService()
            } catch (failure: Throwable) {
                _state.update { it.copy(error = friendlyError(failure), notice = null) }
                syncForegroundService()
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
        syncForegroundService()
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
        val generationAtStart = generation.incrementAndGet()
        val transportLabel = when {
            host.appServerTransport == "websocket" && host.webSocketViaSsh ->
                "SSH Tunnel WebSocket"
            host.appServerTransport == "websocket" -> "WebSocket"
            else -> "SSH stdio"
        }
        _state.update {
            it.copy(
                connectionPhase = if (reconnect) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
                connectionMessage = if (reconnect) {
                    "网络已恢复，正在通过 $transportLabel 重新同步 ${host.label}…"
                } else {
                    "正在通过 $transportLabel 连接 ${host.label}…"
                },
                activeHostId = hostId,
                error = null,
                pendingPrompt = null,
            )
        }
        syncForegroundService()
        try {
            val needsSsh = host.appServerTransport == "ssh" || host.webSocketViaSsh
            val sshConnection = if (needsSsh) ssh.connect(hostId, prompts) else null
            if (generationAtStart != generation.get()) return
            val transport: JsonLineTransport = if (host.appServerTransport == "ssh") {
                withContext(Dispatchers.IO) {
                    requireNotNull(sshConnection).openAppServer(host.codexExecutable)
                }
            } else {
                var effectiveUrl = host.appServerUrl
                if (host.webSocketViaSsh) {
                    val uri = URI(host.appServerUrl)
                    val port = if (uri.port >= 0) uri.port else 80
                    val tunnel = withContext(Dispatchers.IO) {
                        requireNotNull(sshConnection)
                            .openLocalTunnel(requireNotNull(uri.host), port)
                    }
                    localTunnel = tunnel
                    effectiveUrl = tunneledWebSocketUrl(host.appServerUrl, tunnel.localPort)
                }
                val token = local.appServerToken(host)
                WebSocketJsonLineTransport.connect(
                    url = effectiveUrl,
                    bearerToken = token,
                    connectTimeout = host.connectTimeoutMs.milliseconds,
                    allowInsecure = host.allowInsecureWebSocket,
                )
            }
            appServerTransport = transport
            if (transport is SshJsonLineTransport) {
                diagnosticJob = scope.launch {
                    transport.diagnostics.collect { line ->
                        if (generationAtStart == generation.get()) {
                            _state.update { it.copy(lastDiagnostic = line.take(2_000)) }
                        }
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
                val pendingDeltas = linkedMapOf<DeltaKey, StringBuilder>()
                var flushJob: Job? = null

                fun flushDeltas() {
                    if (pendingDeltas.isEmpty()) return
                    val batch = pendingDeltas.mapValues { it.value.toString() }
                    pendingDeltas.clear()
                    _state.update { current -> applyDeltas(current, batch) }
                }

                fun scheduleDeltaFlush() {
                    if (flushJob?.isActive == true) return
                    flushJob = launch {
                        delay(DELTA_FLUSH_INTERVAL_MS)
                        flushJob = null
                        flushDeltas()
                    }
                }

                client.events.collect { event ->
                    if (client !== appServer) return@collect
                    val delta = event.deltaKey()
                    if (delta != null) {
                        pendingDeltas.getOrPut(delta) { StringBuilder() }.append(event.deltaText())
                        scheduleDeltaFlush()
                    } else {
                        // Item lifecycle and turn boundaries must remain ordered after every
                        // delta that preceded them on the wire.
                        flushJob?.cancel()
                        flushJob = null
                        flushDeltas()
                        handleEvent(event)
                    }
                }
                flushDeltas()
            }
            client.initialize()
            if (generationAtStart != generation.get()) return

            val threadPage = client.listThreadsPage(limit = 60)
            val threads = threadPage.threads
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
                        append(" · $transportLabel · Codex ")
                        append(BuildConfig.VERSION_NAME)
                    },
                    threads = threads,
                    threadNextCursor = threadPage.nextCursor,
                    showArchivedThreads = false,
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
            syncForegroundService()
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
            syncForegroundService()
            if (!reconnect && !userDisconnected && isReconnectable(failure)) {
                scheduleReconnect(hostId)
            }
        }
    }

    private fun handleEvent(event: CodexEvent) {
        when (event) {
            is CodexEvent.ItemUpsert -> _state.update { state ->
                state.withThreadView(event.threadId) { view ->
                    view.copy(
                        turns = trimTurns(
                            upsertItem(
                                turns = view.turns,
                                item = event.item,
                                activeTurnId = view.activeTurnId,
                                preferredTurnId = event.turnId,
                            ),
                        ),
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }
            }
            is CodexEvent.TurnChanged -> _state.update {
                it.withThreadView(event.threadId) { view ->
                    view.copy(
                        turns = updateTurnStatus(view.turns, event.turnId, event.status),
                        activeTurnId = if (event.running) event.turnId ?: view.activeTurnId else null,
                        isTurnRunning = event.running,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }.let { updated ->
                    if (event.error != null) updated.copy(error = event.error) else updated
                }
            }.also { syncForegroundService() }
            is CodexEvent.ThreadChanged -> scheduleThreadRefresh()
            is CodexEvent.ThreadSettingsChanged -> {
                val threadId = event.threadId ?: return
                _state.update { current ->
                    val updated = current.withThreadView(threadId) { view ->
                        view.copy(
                            session = view.session?.withSettings(event.settings),
                            lastUsedAt = System.currentTimeMillis(),
                        )
                    }
                    if (updated.selectedThreadId == threadId) {
                        updated.withSelectedSettings(event.settings)
                    } else {
                        updated
                    }
                }
            }
            is CodexEvent.TurnDiffChanged -> _state.update { current ->
                current.withThreadView(event.threadId) { view ->
                    view.copy(
                        turns = updateTurnDiff(view.turns, event.turnId, event.diff),
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }
            }
            is CodexEvent.ApprovalRequested -> _state.update { current ->
                val key = event.request.approvalKey()
                current.copy(
                    approvals = current.approvals.filterNot {
                        it.approvalKey() == key
                    } + event.request,
                )
            }
            is CodexEvent.ApprovalResolved -> _state.update { current ->
                val key = event.requestId.approvalKey()
                current.copy(
                    approvals = current.approvals.filterNot {
                        it.requestId.approvalKey() == key
                    },
                    respondingApprovalIds = current.respondingApprovalIds.filterNot {
                        it == key
                    }.toSet(),
                )
            }
            is CodexEvent.Warning -> _state.update { it.copy(notice = event.message) }
            is CodexEvent.ServerError -> _state.update { current ->
                if (event.willRetry) {
                    current.copy(notice = "Codex 暂时错误，服务器正在重试：${event.message}")
                } else {
                    current.copy(error = event.message)
                }
            }
            is CodexEvent.Diagnostic -> _state.update {
                it.copy(lastDiagnostic = event.message.take(2_000))
            }
            is CodexEvent.Disconnected -> {
                if (!userDisconnected && state.value.connectionPhase != ConnectionPhase.DISCONNECTED) {
                    scheduleReconnect(state.value.activeHostId)
                }
            }
            is CodexEvent.Initialized -> Unit
            is CodexEvent.AgentDelta,
            is CodexEvent.PlanDelta,
            is CodexEvent.ReasoningDelta,
            is CodexEvent.CommandOutputDelta,
            -> Unit // Folded by the batching event collector.
        }
    }

    private fun applyDeltas(state: AppState, batch: Map<DeltaKey, String>): AppState {
        var updated = state
        for ((key, delta) in batch) {
            if (key.itemId.isBlank() || delta.isEmpty()) {
                continue
            }
            updated = updated.withThreadView(key.threadId) { view ->
                view.copy(
                    turns = trimTurns(
                        appendDelta(view.turns, view.activeTurnId, key.itemId, key.field, delta),
                    ),
                    lastUsedAt = System.currentTimeMillis(),
                )
            }
        }
        return updated
    }

    private fun appendDelta(
        turns: List<ThreadTurn>,
        activeTurnId: String?,
        itemId: String,
        field: String,
        delta: String,
    ): List<ThreadTurn> {
        val existingTurnIndex = turns.indexOfFirst { turn -> turn.items.any { it.id == itemId } }
        if (existingTurnIndex >= 0) {
            return turns.toMutableList().also { copy ->
                val turn = copy[existingTurnIndex]
                copy[existingTurnIndex] = turn.copy(
                    items = turn.items.map { item ->
                        if (item.id != itemId) item else {
                            val previous = item.raw[field]?.let {
                                runCatching { (it as JsonPrimitive).content }.getOrNull()
                            }.orEmpty()
                            val updated = appendBounded(previous, delta)
                            item.copy(raw = JsonObject(item.raw + (field to JsonPrimitive(updated))))
                        }
                    },
                )
            }
        }
        val transient = ThreadItem(
            id = itemId,
            type = when (field) {
                "aggregatedOutput" -> "commandExecution"
                "reasoningSummary", "reasoningText" -> "reasoning"
                else -> "agentMessage"
            },
            status = "inProgress",
            raw = buildJsonObject { put(field, appendBounded("", delta)) },
        )
        return upsertItem(turns, transient, activeTurnId)
    }

    fun openThread(threadId: String) {
        val client = appServer ?: return showError("请先连接服务器")
        if (state.value.isLoadingThread && state.value.selectedThreadId == threadId) return
        scope.launch {
            _state.update { current ->
                val now = System.currentTimeMillis()
                val previous = current.activeThreadView()?.copy(lastUsedAt = now)
                val views = trimThreadViews(
                    current.threadViews + listOfNotNull(previous?.let { it.threadId to it }).toMap(),
                    activeThreadId = threadId,
                )
                val cached = views[threadId]?.copy(lastUsedAt = now)
                    ?: ThreadViewState(threadId = threadId, lastUsedAt = now)
                current.copy(
                    threadViews = trimThreadViews(views + (threadId to cached), threadId),
                    isLoadingThread = true,
                    selectedThreadId = threadId,
                    selectedSession = cached?.session,
                    turns = cached?.turns.orEmpty(),
                    olderHistoryCursor = cached?.olderHistoryCursor,
                    activeTurnId = cached?.activeTurnId,
                    isTurnRunning = cached?.isTurnRunning == true,
                    error = null,
                )
            }
            try {
                val session = client.resumeThread(
                    threadId = threadId,
                )
                val page = client.listTurns(threadId, limit = 20, sortDirection = "desc")
                val chronological = page.turns.asReversed()
                _state.update { current ->
                    val existing = current.threadViews[threadId]
                    val turns = mergeTurns(
                        existing?.turns.orEmpty(),
                        session.turns,
                        chronological,
                    )
                    val activeTurn = turns.lastOrNull { it.status == "inProgress" }
                    val view = ThreadViewState(
                        threadId = threadId,
                        session = session,
                        turns = turns,
                        olderHistoryCursor = page.nextCursor ?: existing?.olderHistoryCursor,
                        activeTurnId = activeTurn?.id ?: existing?.activeTurnId,
                        isTurnRunning = activeTurn != null,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                    val updated = current.copy(
                        threadViews = trimThreadViews(
                            current.threadViews + (threadId to view),
                            activeThreadId = current.selectedThreadId,
                        ),
                    )
                    if (current.selectedThreadId != threadId) updated else updated.copy(
                        selectedSession = session,
                        turns = view.turns,
                        olderHistoryCursor = view.olderHistoryCursor,
                        activeTurnId = view.activeTurnId,
                        isTurnRunning = view.isTurnRunning,
                        isLoadingThread = false,
                    ).withSelectedSession(session)
                }
                syncForegroundService()
                state.value.activeHostId?.let {
                    settingsStore.rememberConnection(it, threadId)
                }
            } catch (failure: Throwable) {
                _state.update {
                    if (it.selectedThreadId == threadId) {
                        it.copy(isLoadingThread = false, error = friendlyError(failure))
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun closeThread() {
        _state.update {
            val active = it.activeThreadView()?.copy(lastUsedAt = System.currentTimeMillis())
            it.copy(
                selectedThreadId = null,
                selectedSession = null,
                turns = emptyList(),
                activeTurnId = null,
                isTurnRunning = false,
                olderHistoryCursor = null,
                isLoadingThread = false,
                isLoadingHistory = false,
                isSending = false,
                threadViews = trimThreadViews(
                    if (active == null) it.threadViews
                    else it.threadViews + (active.threadId to active),
                    activeThreadId = null,
                ),
                approvals = emptyList(),
                respondingApprovalIds = emptySet(),
            )
        }
        state.value.activeHostId?.let { settingsStore.rememberConnection(it, threadId = null) }
        syncForegroundService()
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
                _state.update { current ->
                    current.withThreadView(threadId) { view ->
                        view.copy(
                            turns = mergeTurns(page.turns.asReversed(), view.turns),
                            olderHistoryCursor = page.nextCursor,
                            lastUsedAt = System.currentTimeMillis(),
                        )
                    }.copy(
                        isLoadingHistory = if (current.selectedThreadId == threadId) {
                            false
                        } else {
                            current.isLoadingHistory
                        },
                    )
                }
                syncForegroundService()
            } catch (failure: Throwable) {
                _state.update { it.copy(isLoadingHistory = false, error = friendlyError(failure)) }
            }
        }
    }

    fun startThread(cwd: String) {
        val client = appServer ?: return showError("请先连接服务器")
        val path = cwd.trim()
        if (!path.startsWith('/')) return showError("项目路径必须是以 / 开头的远程绝对路径")
        if (!claimBusy()) return
        scope.launch {
            try {
                val snapshot = state.value
                val session = client.startThread(
                    ThreadStartOptions(
                        cwd = path,
                        model = snapshot.selectedModelId,
                        approvalPolicy = snapshot.approvalPolicyValue,
                        approvalsReviewer = snapshot.approvalsReviewer,
                        sandbox = snapshot.sandbox,
                        permissionProfile = snapshot.selectedPermissionProfileId,
                    ),
                )
                _state.update {
                    val active = it.activeThreadView()
                    val view = ThreadViewState(
                        threadId = session.thread.id,
                        session = session,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                    it.copy(
                        threads = (it.threads + session.thread).distinctBy(CodexThread::id),
                        threadViews = trimThreadViews(
                            it.threadViews +
                                listOfNotNull(active?.let { item -> item.threadId to item }).toMap() +
                                (session.thread.id to view),
                            activeThreadId = session.thread.id,
                        ),
                        selectedThreadId = session.thread.id,
                        selectedSession = session,
                        turns = emptyList(),
                        activeTurnId = null,
                        isTurnRunning = false,
                        olderHistoryCursor = null,
                        busy = false,
                    ).withSelectedSession(session)
                }
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = friendlyError(failure)) }
            }
        }
    }

    fun sendMessage(text: String, imageDataUrls: List<String> = emptyList()) {
        val body = text.trim()
        if (body.isEmpty() && imageDataUrls.isEmpty()) return
        if (imageDataUrls.size > MAX_IMAGE_ATTACHMENTS) {
            return showError("每条消息最多附加 $MAX_IMAGE_ATTACHMENTS 张图片")
        }
        if (imageDataUrls.sumOf(String::length) > MAX_IMAGE_DATA_CHARS) {
            return showError("图片附件总大小超过限制")
        }
        val current = state.value
        if (current.isLoadingThread) return showError("会话仍在同步，请稍候")
        val client = appServer ?: return showError("请先连接服务器")
        val threadId = current.selectedThreadId ?: return showError("请先选择会话")
        val session = current.selectedSession ?: return showError("会话尚未恢复")
        if (!claimSending(threadId)) return
        scope.launch {
            try {
                val snapshot = state.value
                if (snapshot.isTurnRunning && !snapshot.activeTurnId.isNullOrBlank()) {
                    client.steerTurn(threadId, snapshot.activeTurnId, body, imageDataUrls)
                } else {
                    val turn = client.startTurn(
                        threadId = threadId,
                        text = body,
                        options = TurnOptions(
                            cwd = session.cwd,
                            model = snapshot.selectedModelId,
                            reasoningEffort = snapshot.selectedReasoningEffort,
                            approvalPolicy = snapshot.approvalPolicyValue,
                            approvalsReviewer = snapshot.approvalsReviewer,
                            sandboxPolicy = snapshot.sandboxPolicyValue,
                            permissionProfile = snapshot.selectedPermissionProfileId,
                            collaborationMode = collaborationModePayload(snapshot),
                        ),
                        imageDataUrls = imageDataUrls,
                    )
                    _state.update {
                        it.withThreadView(threadId) { view ->
                            view.copy(
                                turns = trimTurns(upsertTurn(view.turns, turn)),
                                activeTurnId = turn.id,
                                isTurnRunning = true,
                                lastUsedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                }
                _state.update {
                    it.copy(
                        isSending = false,
                        composerEpoch = if (it.selectedThreadId == threadId) {
                            it.composerEpoch + 1
                        } else {
                            it.composerEpoch
                        },
                    )
                }
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

    fun reviewUncommittedChanges() {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching {
                client.startReview(
                    threadId = threadId,
                    target = buildJsonObject { put("type", "uncommittedChanges") },
                )
            }.onSuccess { turn ->
                _state.update { current ->
                    current.withThreadView(threadId) { view ->
                        view.copy(
                            turns = trimTurns(upsertTurn(view.turns, turn)),
                            activeTurnId = turn.id,
                            isTurnRunning = turn.status == "inProgress",
                            lastUsedAt = System.currentTimeMillis(),
                        )
                    }
                }
            }.onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun forkThread() {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.forkThread(threadId) }
                .onSuccess { session ->
                    _state.update {
                        val active = it.activeThreadView()
                        val view = ThreadViewState(
                            threadId = session.thread.id,
                            session = session,
                            lastUsedAt = System.currentTimeMillis(),
                        )
                        it.copy(
                            threads = (it.threads + session.thread).distinctBy(CodexThread::id),
                            threadViews = trimThreadViews(
                                it.threadViews +
                                    listOfNotNull(active?.let { item -> item.threadId to item }).toMap() +
                                    (session.thread.id to view),
                                activeThreadId = session.thread.id,
                            ),
                            selectedThreadId = session.thread.id,
                            selectedSession = session,
                            turns = emptyList(),
                            activeTurnId = null,
                            isTurnRunning = false,
                        ).withSelectedSession(session)
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
                            threadViews = it.threadViews - threadId,
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

    fun unarchiveSelectedThread() {
        val client = appServer ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.unarchiveThread(threadId) }
                .onSuccess { thread ->
                    _state.update {
                        it.copy(
                            threads = it.threads.filterNot { item -> item.id == threadId },
                            threadViews = it.threadViews - threadId,
                            selectedThreadId = null,
                            selectedSession = null,
                            turns = emptyList(),
                            notice = "已恢复 ${thread.title}",
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
        val key = request.approvalKey()
        if (key in state.value.respondingApprovalIds) return
        _state.update { it.copy(respondingApprovalIds = it.respondingApprovalIds + key) }
        scope.launch {
            try {
                client.approve(request, decision, answers)
                removeApproval(request)
            } catch (failure: Throwable) {
                _state.update { it.copy(respondingApprovalIds = it.respondingApprovalIds - key) }
                showError(friendlyError(failure))
            }
        }
    }

    fun rejectApproval(request: ApprovalRequest) {
        val client = appServer ?: return
        val key = request.approvalKey()
        if (key in state.value.respondingApprovalIds) return
        _state.update { it.copy(respondingApprovalIds = it.respondingApprovalIds + key) }
        scope.launch {
            try {
                if (request.kind == ApprovalKind.UNKNOWN) {
                    client.rejectServerRequest(request, "Unsupported server request")
                } else {
                    client.approve(request, decision = "decline")
                }
                removeApproval(request)
            } catch (failure: Throwable) {
                _state.update { it.copy(respondingApprovalIds = it.respondingApprovalIds - key) }
                showError(friendlyError(failure))
            }
        }
    }

    private fun removeApproval(request: ApprovalRequest) {
        _state.update { state ->
            state.copy(
                approvals = state.approvals.filterNot {
                    it.approvalKey() == request.approvalKey()
                },
                respondingApprovalIds = state.respondingApprovalIds - request.approvalKey(),
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

    fun saveHost(
        draft: HostDraft,
        password: String,
        replacePassword: Boolean,
        appServerToken: String,
        replaceAppServerToken: Boolean,
        done: (Boolean) -> Unit = {},
    ) {
        if (!claimBusy()) {
            done(false)
            return
        }
        scope.launch {
            try {
                local.saveHost(
                    draft = draft,
                    password = password.takeIf { replacePassword }?.toCharArray(),
                    replacePassword = replacePassword,
                    appServerToken = appServerToken.takeIf { replaceAppServerToken }?.toCharArray(),
                    replaceAppServerToken = replaceAppServerToken,
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
            appServerToken = "",
            replaceAppServerToken = false,
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
        if (!claimBusy()) {
            pem.fill(0)
            done(false)
            return
        }
        scope.launch {
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
        if (!claimBusy()) {
            done(false)
            return
        }
        scope.launch {
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

    fun loadCapabilities() {
        val client = appServer ?: return
        val cwd = state.value.selectedSession?.cwd
            ?: state.value.selectedThread?.cwd
            ?: return showError("请先选择一个 Codex 会话")
        val threadId = state.value.selectedThreadId
        if (state.value.isLoadingCapabilities) return
        _state.update { it.copy(isLoadingCapabilities = true) }
        scope.launch {
            val unavailable = linkedMapOf<String, String>()
            val skills = runCatching { client.listSkills(cwd) }
                .onFailure { unavailable["Skills"] = friendlyError(it) }
                .getOrDefault(emptyList())
            val mcpServers = runCatching { client.listMcpServers(threadId) }
                .onFailure { unavailable["MCP"] = friendlyError(it) }
                .getOrDefault(emptyList())
            val plugins = runCatching { client.listPlugins(cwd) }
                .onFailure { unavailable["Plugins"] = friendlyError(it) }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(
                    capabilities = CapabilitySnapshot(
                        cwd = cwd,
                        skills = skills,
                        mcpServers = mcpServers,
                        plugins = plugins,
                        unavailable = unavailable,
                    ),
                    isLoadingCapabilities = false,
                )
            }
        }
    }

    fun dismissCapabilities() = _state.update {
        it.copy(capabilities = null, isLoadingCapabilities = false)
    }

    fun updateSettings(settings: AppSettings) {
        settingsStore.write(settings)
        _state.update { it.copy(settings = settings) }
    }

    fun setThreadSearch(value: String) {
        _state.update { it.copy(threadSearch = value) }
        if (appServer == null) return
        threadSearchJob?.cancel()
        threadSearchJob = scope.launch {
            if (value.isNotBlank()) delay(350)
            loadThreadPage(reset = true)
        }
    }

    fun loadMoreThreads() {
        if (state.value.threadNextCursor == null) return
        loadThreadPage(reset = false)
    }

    fun toggleArchivedThreads() {
        _state.update {
            it.copy(
                showArchivedThreads = !it.showArchivedThreads,
                threads = emptyList(),
                threadNextCursor = null,
            )
        }
        loadThreadPage(reset = true)
    }

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
    fun setApprovalPolicy(value: String) = _state.update {
        it.copy(approvalPolicy = value, approvalPolicyValue = JsonPrimitive(value))
    }
    fun setApprovalsReviewer(value: String) = _state.update { it.copy(approvalsReviewer = value) }
    fun setSandbox(value: String) = _state.update {
        it.copy(sandbox = value, sandboxPolicyValue = sandboxPolicy(value))
    }
    fun setPermissionProfile(value: String?) = _state.update { it.copy(selectedPermissionProfileId = value) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun refreshThreadsNow() {
        loadThreadPage(reset = true)
    }

    private fun loadThreadPage(reset: Boolean) {
        val client = appServer ?: return
        threadListJob?.cancel()
        val snapshot = state.value
        if (!reset && snapshot.isLoadingThreads) return
        val cursor = if (reset) null else snapshot.threadNextCursor ?: return
        threadListJob = scope.launch {
            _state.update { it.copy(isLoadingThreads = true) }
            try {
                val page = client.listThreadsPage(
                    archived = snapshot.showArchivedThreads,
                    searchTerm = snapshot.threadSearch,
                    cursor = cursor,
                    limit = 60,
                )
                if (state.value.threadSearch != snapshot.threadSearch ||
                    state.value.showArchivedThreads != snapshot.showArchivedThreads
                ) {
                    return@launch
                }
                if (!snapshot.showArchivedThreads && snapshot.threadSearch.isBlank()) {
                    state.value.activeHostId?.let { local.cacheThreads(it, page.threads) }
                }
                _state.update { state ->
                    val byId = page.threads.associateBy(CodexThread::id)
                    val threads = if (reset) {
                        page.threads
                    } else {
                        (state.threads + page.threads)
                            .associateBy(CodexThread::id)
                            .values
                            .sortedByDescending(CodexThread::updatedAt)
                    }
                    val threadViews = state.threadViews.mapValues { (_, view) ->
                        byId[view.threadId]?.let { thread ->
                            view.copy(session = view.session?.copy(thread = thread))
                        } ?: view
                    }
                    state.copy(
                        threads = threads,
                        threadViews = threadViews,
                        threadNextCursor = page.nextCursor,
                        isLoadingThreads = false,
                        selectedSession = state.selectedSession?.let { session ->
                            byId[session.thread.id]?.let { session.copy(thread = it) } ?: session
                        },
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(isLoadingThreads = false, error = friendlyError(failure))
                }
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
                syncForegroundService()
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
            syncForegroundService()
        }
    }

    private fun closeCodexConnection() {
        generation.incrementAndGet()
        eventJob?.cancel()
        eventJob = null
        diagnosticJob?.cancel()
        diagnosticJob = null
        threadRefreshJob?.cancel()
        threadRefreshJob = null
        threadSearchJob?.cancel()
        threadSearchJob = null
        threadListJob?.cancel()
        threadListJob = null
        val closingClient = appServer
        val closingTransport = appServerTransport
        val closingTunnel = localTunnel
        appServer = null
        appServerTransport = null
        localTunnel = null
        cleanupScope.launch {
            runCatching {
                if (closingClient != null) {
                    closingClient.close()
                } else {
                    closingTransport?.close()
                }
            }
            runCatching { closingTunnel?.close() }
        }
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

    private fun claimBusy(): Boolean = synchronized(controlLock) {
        val current = _state.value
        if (current.busy) {
            false
        } else {
            _state.value = current.copy(busy = true, error = null)
            true
        }
    }

    private fun claimSending(threadId: String): Boolean = synchronized(controlLock) {
        val current = _state.value
        if (current.isSending ||
            current.isLoadingThread ||
            current.selectedThreadId != threadId ||
            current.selectedSession == null
        ) {
            false
        } else {
            _state.value = current.copy(isSending = true, error = null)
            true
        }
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

    private fun isReconnectable(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is SecurityException,
                is net.schmizz.sshj.userauth.UserAuthException,
                is IllegalArgumentException,
                is RpcException,
                -> return false
            }
            current = current.cause
        }
        return true
    }

    fun close() {
        userDisconnected = true
        ConnectionForegroundService.stop(appContext)
        closeCodexConnection()
        val terminals = _state.value.terminalSessions
        val sshConnections = ssh.detachAllConnections()
        cleanupScope.launch {
            terminals.forEach { runCatching { it.controller.close() } }
            sshConnections.forEach { runCatching { it.close() } }
        }
        scope.cancel()
        ssh.close()
        prompts.cancelAll()
    }

    private fun syncForegroundService() {
        val current = state.value
        val connectionActive = current.connectionPhase in setOf(
            ConnectionPhase.CONNECTING,
            ConnectionPhase.CONNECTED,
            ConnectionPhase.RECONNECTING,
        )
        val active = !userDisconnected &&
            (connectionActive || current.terminalSessions.isNotEmpty())
        if (!active) {
            ConnectionForegroundService.stop(appContext)
            return
        }
        val runningView = current.threadViews.values.firstOrNull { it.isTurnRunning }
        val title = when {
            current.isTurnRunning || runningView != null -> "Codex Turn 正在执行"
            current.terminalSessions.isNotEmpty() -> "远程终端已连接"
            current.connectionPhase == ConnectionPhase.CONNECTED -> "Codex 远程连接已建立"
            else -> "CodexDeck 正在连接"
        }
        val detail = when {
            current.isTurnRunning ->
                current.selectedThread?.title ?: current.connectionMessage
            runningView != null ->
                current.threads.firstOrNull { it.id == runningView.threadId }?.title
                    ?: current.connectionMessage
            current.terminalSessions.isNotEmpty() ->
                "${current.terminalSessions.size} 个终端 · ${current.connectionMessage}"
            else -> current.connectionMessage
        }
        ConnectionForegroundService.start(appContext, title, detail)
    }
}

internal fun RequestId.approvalKey(): String = when (this) {
    is RequestId.Number -> "Number:$value"
    is RequestId.Text -> "Text:$value"
}

internal fun ApprovalRequest.approvalKey(): String = requestId.approvalKey()

private fun upsertItem(
    turns: List<ThreadTurn>,
    item: ThreadItem,
    activeTurnId: String?,
    preferredTurnId: String? = null,
): List<ThreadTurn> {
    val existingTurn = turns.indexOfFirst { turn -> turn.items.any { it.id == item.id } }
    if (existingTurn >= 0) {
        return turns.toMutableList().also { copy ->
            val turn = copy[existingTurn]
            copy[existingTurn] = turn.copy(items = turn.items.map { if (it.id == item.id) item else it })
        }
    }
    val targetIndex = turns.indexOfLast { it.id == preferredTurnId }
        .takeIf { it >= 0 }
        ?: turns.indexOfLast { it.id == activeTurnId }
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

private fun mergeTurns(vararg pages: List<ThreadTurn>): List<ThreadTurn> =
    trimTurns(
        pages.flatMap { it }.associateBy(ThreadTurn::id).values
            .sortedBy { it.startedAt ?: Long.MAX_VALUE },
    )

private data class DeltaKey(
    val threadId: String?,
    val itemId: String,
    val field: String,
)

private fun CodexEvent.deltaKey(): DeltaKey? = when (this) {
    is CodexEvent.AgentDelta -> DeltaKey(threadId, itemId, "text")
    is CodexEvent.PlanDelta -> DeltaKey(threadId, itemId, "text")
    is CodexEvent.ReasoningDelta -> DeltaKey(
        threadId,
        itemId,
        if (summary) "reasoningSummary" else "reasoningText",
    )
    is CodexEvent.CommandOutputDelta -> DeltaKey(threadId, itemId, "aggregatedOutput")
    else -> null
}

private fun CodexEvent.deltaText(): String = when (this) {
    is CodexEvent.AgentDelta -> delta
    is CodexEvent.PlanDelta -> delta
    is CodexEvent.ReasoningDelta -> delta
    is CodexEvent.CommandOutputDelta -> delta
    else -> ""
}

private fun AppState.activeThreadView(): ThreadViewState? {
    val id = selectedThreadId ?: return null
    return ThreadViewState(
        threadId = id,
        session = selectedSession,
        turns = turns,
        olderHistoryCursor = olderHistoryCursor,
        activeTurnId = activeTurnId,
        isTurnRunning = isTurnRunning,
        lastUsedAt = threadViews[id]?.lastUsedAt ?: System.currentTimeMillis(),
    )
}

private fun AppState.withThreadView(
    threadId: String?,
    transform: (ThreadViewState) -> ThreadViewState,
): AppState {
    val id = threadId ?: selectedThreadId ?: return this
    val active = selectedThreadId == id
    val current = if (active) activeThreadView() ?: return this else threadViews[id] ?: return this
    val updated = transform(current)
    val views = trimThreadViews(threadViews + (id to updated), selectedThreadId)
    return if (!active) {
        copy(threadViews = views)
    } else {
        copy(
            threadViews = views,
            selectedSession = updated.session,
            turns = updated.turns,
            olderHistoryCursor = updated.olderHistoryCursor,
            activeTurnId = updated.activeTurnId,
            isTurnRunning = updated.isTurnRunning,
        )
    }
}

internal fun trimThreadViews(
    views: Map<String, ThreadViewState>,
    activeThreadId: String?,
): Map<String, ThreadViewState> {
    if (views.size <= MAX_CACHED_THREAD_VIEWS) return views
    val removable = views.values
        .asSequence()
        .filter { it.threadId != activeThreadId }
        .sortedBy(ThreadViewState::lastUsedAt)
        .take(views.size - MAX_CACHED_THREAD_VIEWS)
        .map(ThreadViewState::threadId)
        .toSet()
    return views.filterKeys { it !in removable }
}

private fun appendBounded(previous: String, delta: String): String {
    if (delta.isEmpty()) return previous
    if (previous.length + delta.length <= MAX_ITEM_TEXT_CHARS) return previous + delta
    val keep = (MAX_ITEM_TEXT_CHARS - TRUNCATION_MARKER.length).coerceAtLeast(0)
    return TRUNCATION_MARKER + (previous + delta).takeLast(keep)
}

private fun updateTurnStatus(
    turns: List<ThreadTurn>,
    turnId: String?,
    status: String?,
): List<ThreadTurn> {
    if (turnId == null || status == null) return turns
    val index = turns.indexOfFirst { it.id == turnId }
    if (index < 0) return turns
    return turns.toMutableList().also { copy ->
        copy[index] = copy[index].copy(status = status)
    }
}

private fun updateTurnDiff(
    turns: List<ThreadTurn>,
    turnId: String,
    diff: String,
): List<ThreadTurn> {
    val index = turns.indexOfFirst { it.id == turnId }
    if (index < 0) return turns
    return turns.toMutableList().also { copy ->
        val turn = copy[index]
        copy[index] = turn.copy(
            raw = JsonObject(turn.raw + ("diff" to JsonPrimitive(diff))),
        )
    }
}

private fun AppState.withSelectedSession(session: ThreadSession): AppState = copy(
    selectedModelId = session.model ?: selectedModelId,
    selectedReasoningEffort = session.reasoningEffort ?: selectedReasoningEffort,
    selectedCollaborationMode = session.collaborationMode ?: selectedCollaborationMode,
    approvalPolicy = approvalPolicyLabel(session.approvalPolicy ?: approvalPolicyValue),
    approvalPolicyValue = session.approvalPolicy ?: approvalPolicyValue,
    approvalsReviewer = session.approvalsReviewer ?: approvalsReviewer,
    sandbox = sandboxMode(session.sandbox) ?: sandbox,
    sandboxPolicyValue = session.sandbox ?: sandboxPolicyValue,
    selectedPermissionProfileId = session.permissionProfile,
)

private fun AppState.withSelectedSettings(settings: ThreadSettingsSnapshot): AppState = copy(
    selectedModelId = settings.model ?: selectedModelId,
    selectedReasoningEffort = settings.reasoningEffort ?: selectedReasoningEffort,
    selectedCollaborationMode = settings.collaborationMode ?: selectedCollaborationMode,
    approvalPolicy = approvalPolicyLabel(settings.approvalPolicy ?: approvalPolicyValue),
    approvalPolicyValue = settings.approvalPolicy ?: approvalPolicyValue,
    approvalsReviewer = settings.approvalsReviewer ?: approvalsReviewer,
    sandbox = settings.sandboxMode ?: sandbox,
    sandboxPolicyValue = settings.sandboxPolicy ?: sandboxPolicyValue,
    selectedPermissionProfileId = settings.permissionProfile,
)

private fun ThreadSession.withSettings(settings: ThreadSettingsSnapshot): ThreadSession = copy(
    model = settings.model ?: model,
    modelProvider = settings.modelProvider ?: modelProvider,
    reasoningEffort = settings.reasoningEffort ?: reasoningEffort,
    cwd = settings.cwd ?: cwd,
    approvalPolicy = settings.approvalPolicy ?: approvalPolicy,
    approvalsReviewer = settings.approvalsReviewer ?: approvalsReviewer,
    sandbox = settings.sandboxPolicy ?: sandbox,
    collaborationMode = settings.collaborationMode ?: collaborationMode,
    permissionProfile = settings.permissionProfile,
)

private fun sandboxMode(policy: JsonObject?): String? = when (
    policy?.get("type")?.let { runCatching { (it as JsonPrimitive).content }.getOrNull() }
) {
    "readOnly" -> "read-only"
    "workspaceWrite" -> "workspace-write"
    "dangerFullAccess" -> "danger-full-access"
    "externalSandbox" -> "external-sandbox"
    else -> null
}

internal fun approvalPolicyLabel(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.content
    is JsonObject -> "granular"
    else -> value.toString()
}

internal fun sandboxModeLabel(mode: String): String = when (mode) {
    "read-only" -> "只读"
    "danger-full-access" -> "完全访问"
    "external-sandbox" -> "外部沙箱"
    else -> "工作区写入"
}

private fun sandboxPolicy(mode: String): JsonObject = buildJsonObject {
    put(
        "type",
        when (mode) {
            "read-only" -> "readOnly"
            "danger-full-access" -> "dangerFullAccess"
            else -> "workspaceWrite"
        },
    )
}

internal fun trimTurns(turns: List<ThreadTurn>): List<ThreadTurn> =
    if (turns.size <= MAX_TURNS_IN_MEMORY) turns else turns.takeLast(MAX_TURNS_IN_MEMORY)

internal fun tunneledWebSocketUrl(remoteUrl: String, localPort: Int): String {
    require(localPort in 1..65535) { "Local tunnel port is invalid" }
    val uri = URI(remoteUrl)
    require(uri.scheme.equals("ws", ignoreCase = true)) {
        "SSH WebSocket tunnel requires ws://"
    }
    val path = uri.rawPath.orEmpty().ifBlank { "/" }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "ws://127.0.0.1:$localPort$path$query"
}
