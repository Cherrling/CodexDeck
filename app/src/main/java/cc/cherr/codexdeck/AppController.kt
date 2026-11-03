package cc.cherr.codexdeck

import android.content.Context
import androidx.room.Room
import cc.cherr.codexdeck.data.AppSettings
import cc.cherr.codexdeck.data.AppServerTransportMode
import cc.cherr.codexdeck.data.CodexDeckDatabase
import cc.cherr.codexdeck.data.CredentialVault
import cc.cherr.codexdeck.data.HostDraft
import cc.cherr.codexdeck.data.IdentityGeneration
import cc.cherr.codexdeck.data.LocalRepository
import cc.cherr.codexdeck.data.SettingsStore
import cc.cherr.codexdeck.diagnostics.DiagnosticLogStore
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
import cc.cherr.codexdeck.protocol.RpcTraceEvent
import cc.cherr.codexdeck.protocol.ThreadItem
import cc.cherr.codexdeck.protocol.ThreadItemEntry
import cc.cherr.codexdeck.protocol.ThreadSession
import cc.cherr.codexdeck.protocol.ThreadSettingsSnapshot
import cc.cherr.codexdeck.protocol.ThreadStartOptions
import cc.cherr.codexdeck.protocol.ThreadTurn
import cc.cherr.codexdeck.protocol.TurnOptions
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.ssh.ConnectionPromptGateway
import cc.cherr.codexdeck.ssh.DiagnosticTransport
import cc.cherr.codexdeck.ssh.SshAuthenticationCancelled
import cc.cherr.codexdeck.ssh.SshConnectionManager
import cc.cherr.codexdeck.ssh.SshJsonLineTransport
import cc.cherr.codexdeck.ssh.SshLocalTunnel
import cc.cherr.codexdeck.ssh.TrustDecision
import cc.cherr.codexdeck.ssh.usesSshTransport
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
import java.io.File
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
private const val INITIAL_HISTORY_TURNS = 5
private const val INITIAL_HISTORY_ITEMS = 40
private const val OLDER_HISTORY_ITEMS = 50
private const val OLDER_HISTORY_TURNS = 20

private class HostRuntime(val hostId: String) {
    val generation = AtomicInteger()
    var client: AppServerClient? = null
    var transport: JsonLineTransport? = null
    var localTunnel: SshLocalTunnel? = null
    var eventJob: Job? = null
    var diagnosticJob: Job? = null
    var connectJob: Job? = null
    var reconnectJob: Job? = null
    var threadRefreshJob: Job? = null
    var threadSearchJob: Job? = null
    var threadListJob: Job? = null
    var userDisconnected = true
}

class AppController(
    context: Context,
    dao: cc.cherr.codexdeck.data.StoreDao,
    private val vault: CredentialVault,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore = SettingsStore(appContext)
    private val diagnosticLog = DiagnosticLogStore(File(appContext.filesDir, "diagnostic.log"))
    private val local = LocalRepository(dao, vault)
    private val ssh = SshConnectionManager(dao, vault)
    private val prompts = ConnectionPromptGateway()
    private val controlLock = Any()
    private val _state = MutableStateFlow(AppState(settings = settingsStore.read()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val runtimes = mutableMapOf<String, HostRuntime>()
    private var restoreAttempted = false
    private val previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, failure ->
        diagnosticLog.logFatal(
            "crash",
            "Uncaught exception thread=${thread.name} ${failure.stackTraceToString()}",
        )
        previousUncaughtExceptionHandler?.uncaughtException(thread, failure)
            ?: Runtime.getRuntime().halt(10)
    }

    init {
        val savedSettings = settingsStore.read()
        diagnosticLog.setEnabled(savedSettings.diagnosticLoggingEnabled)
        if (savedSettings.diagnosticLoggingEnabled) {
            diagnosticLog.log(
                "app",
                "diagnostic logging restored version=${BuildConfig.VERSION_NAME} " +
                    "revision=${BuildConfig.SOURCE_REVISION}",
            )
        }
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
        scope.launch {
            prompts.prompts.collect { prompt ->
                _state.update { it.copy(pendingPrompt = prompt) }
            }
        }
        refreshLocal()
    }

    private fun runtimeFor(hostId: String): HostRuntime =
        runtimes.getOrPut(hostId) { HostRuntime(hostId) }

    private fun activeRuntime(): HostRuntime? =
        state.value.activeHostId?.let(::runtimeFor)

    private fun activeClient(): AppServerClient? = activeRuntime()?.client

    fun selectHost(hostId: String) {
        if (state.value.hosts.none { it.id == hostId }) return
        _state.update { current ->
            val updated = current.activateHost(hostId)
            val threadId = updated.selectedThreadId
            settingsStore.rememberConnection(hostId, threadId)
            updated
        }
    }

    fun connect(hostId: String) {
        val runtime = runtimeFor(hostId)
        if (runtime.connectJob?.isActive == true || runtime.reconnectJob?.isActive == true) return
        runtime.userDisconnected = false
        runtime.reconnectJob?.cancel()
        selectHost(hostId)
        runtime.connectJob = scope.launch {
            connectInternal(hostId = hostId, reconnect = false, reopenThreadId = null)
        }
    }

    fun disconnect(hostId: String? = state.value.activeHostId) {
        val targetHostId = hostId ?: return
        val runtime = runtimeFor(targetHostId)
        diagnosticLog.log("connection", "user disconnect host=$targetHostId")
        runtime.userDisconnected = true
        val wasConnecting = runtime.connectJob?.isActive == true
        runtime.reconnectJob?.cancel()
        runtime.connectJob?.cancel()
        closeHostConnection(targetHostId)
        if (settingsStore.lastHostId() == targetHostId) settingsStore.clearConnection()
        val terminals = _state.value.terminalSessions.filter { it.hostId == targetHostId }
        val remainingTerminals = _state.value.terminalSessions.filterNot { it.hostId == targetHostId }
        val selectedTerminal = _state.value.selectedTerminalId
            ?.takeIf { id -> remainingTerminals.any { it.id == id } }
        cleanupScope.launch {
            terminals.forEach { runCatching { it.controller.close() } }
            runCatching { ssh.close(targetHostId) }
        }
        if (wasConnecting) prompts.cancelAll()
        _state.update {
            it.withHostConnection(targetHostId) { connection ->
                HostConnectionState(
                    hostId = connection.hostId,
                    connectionPhase = ConnectionPhase.DISCONNECTED,
                    connectionMessage = "已断开",
                )
            }.copy(
                pendingPrompt = null,
                terminalSessions = remainingTerminals,
                selectedTerminalId = selectedTerminal,
                busy = false,
                isSending = false,
            )
        }
        syncForegroundService()
    }

    fun disconnectAll() {
        runtimes.keys.toList().forEach(::disconnect)
        ConnectionForegroundService.stop(appContext)
    }

    fun reconnect() {
        state.value.activeHostId?.let(::connect)
    }

    fun restoreLastConnection() {
        if (restoreAttempted) return
        if (!_state.value.settings.autoReconnectOnLaunch) return
        restoreAttempted = true
        val hostId = settingsStore.lastHostId() ?: return
        val threadId = settingsStore.lastThreadId()
        scope.launch {
            if (runtimes.values.any { it.client != null || it.connectJob?.isActive == true }) return@launch
            if (local.host(hostId) == null) {
                settingsStore.clearConnection()
                return@launch
            }
            runtimeFor(hostId).userDisconnected = false
            connectInternal(hostId, reconnect = true, reopenThreadId = threadId)
        }
    }

    fun openTerminal(hostId: String) {
        val host = state.value.hosts.firstOrNull { it.id == hostId } ?: return showError("服务器不存在")
        scope.launch {
            val startedAtNanos = System.nanoTime()
            diagnosticLog.log(
                "shell",
                "open start host=${host.id} label=${host.label} " +
                    "jump=${!host.jumpHostId.isNullOrBlank()}",
            )
            _state.update { it.copy(notice = "正在为 ${host.label} 建立 SSH Shell…", error = null) }
            try {
                val connection = ssh.connect(hostId, prompts)
                val id = UUID.randomUUID().toString()
                var controller: ShellTerminalController? = null
                controller = ShellTerminalController(
                    appContext = appContext,
                    transport = connection.newShellTransport(),
                    initialFontSizeSp = state.value.settings.terminalFontSizeSp,
                    onFinished = {
                        diagnosticLog.log("shell", "closed host=${host.id} terminal=$id")
                        controller?.let { removeTerminal(id, closeController = false) }
                    },
                    onFailure = { message ->
                        diagnosticLog.log("shell", "failure host=${host.id} terminal=$id $message")
                        _state.update { it.copy(error = message, notice = null) }
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
                    )
                }
                diagnosticLog.log(
                    "shell",
                    "opened host=${host.id} terminal=$id durationMs=${elapsedMillis(startedAtNanos)}",
                )
                syncForegroundService()
            } catch (failure: Throwable) {
                logFailure(
                    category = "shell",
                    context = "open host=${host.id} label=${host.label}",
                    failure = failure,
                )
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
        val runtime = runtimeFor(hostId)
        val host = local.host(hostId) ?: run {
            showError("服务器不存在")
            return
        }
        closeHostConnection(hostId)
        val generationAtStart = runtime.generation.incrementAndGet()
        val transportLabel = when {
            host.appServerTransport == AppServerTransportMode.WEBSOCKET && host.webSocketViaSsh ->
                "SSH Tunnel WebSocket"
            host.appServerTransport == AppServerTransportMode.WEBSOCKET -> "WebSocket"
            host.appServerTransport == AppServerTransportMode.SSH_EXISTING -> "SSH 已有 App Server"
            else -> "SSH 启动 App Server"
        }
        val connectStartedAtNanos = System.nanoTime()
        diagnosticLog.log(
            "connection",
            "start host=${host.id} label=${host.label} transport=$transportLabel " +
                "reconnect=$reconnect jump=${!host.jumpHostId.isNullOrBlank()}",
        )
        _state.update { current ->
            val shownHost = if (!reconnect ||
                current.activeHostId == null ||
                current.activeHostId == hostId
            ) {
                current.activateHost(hostId)
            } else {
                current
            }
            shownHost.withHostConnection(hostId) {
                it.copy(
                connectionPhase = if (reconnect) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
                connectionMessage = if (reconnect) {
                    "网络已恢复，正在通过 $transportLabel 重新同步 ${host.label}…"
                } else {
                    "正在通过 $transportLabel 连接 ${host.label}…"
                },
                error = null,
                notice = null,
                )
            }.copy(
                pendingPrompt = null,
            )
        }
        syncForegroundService()
        try {
            val needsSsh = host.usesSshTransport()
            val sshConnection = if (needsSsh) ssh.connect(hostId, prompts) else null
            if (generationAtStart != runtime.generation.get()) return
            if (needsSsh) {
                diagnosticLog.log(
                    "ssh",
                    "authenticated host=${host.id} durationMs=${elapsedMillis(connectStartedAtNanos)}",
                )
            }
            val transport: JsonLineTransport = when (host.appServerTransport) {
                AppServerTransportMode.SSH_LAUNCH -> withContext(Dispatchers.IO) {
                    requireNotNull(sshConnection).openAppServer(host.codexExecutable)
                }
                AppServerTransportMode.SSH_EXISTING -> withContext(Dispatchers.IO) {
                    requireNotNull(sshConnection).openExistingAppServer(
                        executable = host.codexExecutable,
                        connectTimeout = host.connectTimeoutMs.milliseconds,
                    )
                }
                else -> {
                    var effectiveUrl = host.appServerUrl
                    if (host.webSocketViaSsh) {
                        val uri = URI(host.appServerUrl)
                        val port = if (uri.port >= 0) uri.port else 80
                        val tunnel = withContext(Dispatchers.IO) {
                            requireNotNull(sshConnection)
                                .openLocalTunnel(requireNotNull(uri.host), port)
                        }
                        runtime.localTunnel = tunnel
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
            }
            runtime.transport = transport
            diagnosticLog.log(
                "transport",
                "opened host=${host.id} kind=${transport.javaClass.simpleName} " +
                    "durationMs=${elapsedMillis(connectStartedAtNanos)}",
            )
            if (transport is DiagnosticTransport) {
                runtime.diagnosticJob = scope.launch {
                    transport.diagnostics.collect { line ->
                        diagnosticLog.log("ssh-diagnostic", "host=${host.id} $line")
                        if (generationAtStart == runtime.generation.get()) {
                            updateHostConnection(hostId) {
                                it.copy(lastDiagnostic = line.take(2_000))
                            }
                        }
                    }
                }
            }
            val client = AppServerClient(
                transport = transport,
                clientInfo = ClientInfo(
                    version = BuildConfig.VERSION_NAME,
                ),
                onTrace = { event -> logRpcTrace(hostId, event) },
            )
            runtime.client = client
            runtime.eventJob = scope.launch {
                val pendingDeltas = linkedMapOf<DeltaKey, StringBuilder>()
                var flushJob: Job? = null

                fun flushDeltas() {
                    if (pendingDeltas.isEmpty()) return
                    val batch = pendingDeltas.mapValues { it.value.toString() }
                    pendingDeltas.clear()
                    updateHostConnection(hostId) { current -> current.applyDeltas(batch) }
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
                    if (client !== runtime.client) return@collect
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
                        handleEvent(hostId, event)
                    }
                }
                flushDeltas()
            }
            val initialized = client.initialize()
            if (generationAtStart != runtime.generation.get()) return
            diagnosticLog.log(
                "app-server",
                "initialized host=${host.id} userAgent=${initialized.userAgent} " +
                    "platform=${initialized.platformFamily}/${initialized.platformOs}",
            )

            val threadPage = client.listThreadsPage(limit = 60)
            val threads = threadPage.threads
            val models = runCatching { client.listModels() }.getOrDefault(emptyList())
            val modes = runCatching { client.listCollaborationModes() }.getOrDefault(emptyList())
            val profiles = runCatching { client.listPermissionProfiles() }.getOrDefault(emptyList())
            diagnosticLog.log(
                "app-server",
                "catalog host=${host.id} threads=${threads.size} models=${models.size} " +
                    "collaborationModes=${modes.size} permissionProfiles=${profiles.size}",
            )
            local.cacheThreads(hostId, threads)
            local.markUsed(hostId)
            val selectedModel = models.firstOrNull { it.isDefault } ?: models.firstOrNull()
            val projects = groupThreads(threads)
            updateHostConnection(hostId) {
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
                    error = null,
                    notice = null,
                )
            }
            if (state.value.activeHostId == hostId) {
                _state.update { it.copy(pendingPrompt = null) }
            }
            refreshHosts()
            syncForegroundService()
            diagnosticLog.log(
                "connection",
                "connected host=${host.id} transport=$transportLabel " +
                    "durationMs=${elapsedMillis(connectStartedAtNanos)}",
            )
            if (reopenThreadId != null && state.value.activeHostId == hostId) {
                openThread(reopenThreadId)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (generationAtStart != runtime.generation.get()) return
            logFailure(
                category = "connection",
                context = "host=${host.id} label=${host.label} transport=$transportLabel",
                failure = failure,
            )
            closeHostConnection(hostId)
            val message = friendlyError(failure)
            updateHostConnection(hostId) {
                it.copy(
                    connectionPhase = ConnectionPhase.ERROR,
                    connectionMessage = message,
                    error = message,
                )
            }
            if (state.value.activeHostId == hostId) {
                _state.update { it.copy(pendingPrompt = null) }
            }
            syncForegroundService()
            if (!reconnect && !runtime.userDisconnected && isReconnectable(failure)) {
                scheduleReconnect(hostId)
            }
        }
    }

    private fun updateHostConnection(
        hostId: String,
        transform: (HostConnectionState) -> HostConnectionState,
    ) {
        _state.update { current -> current.withHostConnection(hostId, transform) }
    }

    private fun showHostError(hostId: String, message: String) {
        updateHostConnection(hostId) {
            it.copy(error = message, isSending = false)
        }
        if (state.value.activeHostId == hostId) {
            _state.update { it.copy(error = message, busy = false) }
        }
    }

    private fun handleEvent(hostId: String, event: CodexEvent) {
        when (event) {
            is CodexEvent.ItemUpsert -> updateHostConnection(hostId) { connection ->
                connection.withThreadView(event.threadId) { view ->
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
            is CodexEvent.TurnChanged -> {
                diagnosticLog.log(
                    "turn",
                    "changed host=$hostId thread=${event.threadId} turn=${event.turnId} " +
                        "running=${event.running} status=${event.status} " +
                        "error=${event.error.orEmpty()}",
                )
                updateHostConnection(hostId) { connection ->
                    connection.withThreadView(event.threadId) { view ->
                        view.copy(
                            turns = updateTurnStatus(view.turns, event.turnId, event.status),
                            activeTurnId = if (event.running) {
                                event.turnId ?: view.activeTurnId
                            } else {
                                null
                            },
                            isTurnRunning = event.running,
                            lastUsedAt = System.currentTimeMillis(),
                        )
                    }.let { updated ->
                        if (event.error != null) updated.copy(error = event.error) else updated
                    }
                }
                syncForegroundService()
            }
            is CodexEvent.ThreadChanged -> {
                diagnosticLog.log(
                    "thread",
                    "changed host=$hostId thread=${event.threadId} kind=${event.kind}",
                )
                scheduleThreadRefresh(hostId)
            }
            is CodexEvent.ThreadSettingsChanged -> {
                val threadId = event.threadId ?: return
                updateHostConnection(hostId) { current ->
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
            is CodexEvent.TurnDiffChanged -> updateHostConnection(hostId) { current ->
                current.withThreadView(event.threadId) { view ->
                    view.copy(
                        turns = updateTurnDiff(view.turns, event.turnId, event.diff),
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }
            }
            is CodexEvent.ApprovalRequested -> {
                val key = event.request.approvalKey()
                diagnosticLog.log(
                    "approval",
                    "requested host=$hostId method=${event.request.method} " +
                        "kind=${event.request.kind} request=$key",
                )
                updateHostConnection(hostId) { current ->
                    current.copy(
                        approvals = current.approvals.filterNot {
                            it.approvalKey() == key
                        } + event.request,
                    )
                }
            }
            is CodexEvent.ApprovalResolved -> {
                val key = event.requestId.approvalKey()
                diagnosticLog.log(
                    "approval",
                    "resolved host=$hostId request=$key thread=${event.threadId}",
                )
                updateHostConnection(hostId) { current ->
                    current.copy(
                        approvals = current.approvals.filterNot {
                            it.requestId.approvalKey() == key
                        },
                        respondingApprovalIds = current.respondingApprovalIds.filterNot {
                            it == key
                        }.toSet(),
                    )
                }
            }
            is CodexEvent.Warning -> {
                diagnosticLog.log("server-warning", "host=$hostId ${event.message}")
                updateHostConnection(hostId) {
                    it.copy(notice = event.message)
                }
            }
            is CodexEvent.ServerError -> {
                diagnosticLog.log(
                    "server-error",
                    "host=$hostId thread=${event.threadId} code=${event.code} " +
                        "retry=${event.willRetry} message=${event.message}",
                )
                updateHostConnection(hostId) { current ->
                    if (event.willRetry) {
                        current.copy(notice = "Codex 暂时错误，服务器正在重试：${event.message}")
                    } else {
                        current.copy(error = event.message)
                    }
                }
            }
            is CodexEvent.Diagnostic -> {
                diagnosticLog.log("app-server-diagnostic", "host=$hostId ${event.message}")
                updateHostConnection(hostId) {
                    it.copy(lastDiagnostic = event.message.take(2_000))
                }
            }
            is CodexEvent.Disconnected -> {
                val runtime = runtimeFor(hostId)
                diagnosticLog.log(
                    "connection",
                    "disconnected host=$hostId cause=${event.cause?.let(::throwableSummary).orEmpty()}",
                )
                if (!runtime.userDisconnected &&
                    state.value.connectionState(hostId).connectionPhase != ConnectionPhase.DISCONNECTED
                ) {
                    scheduleReconnect(hostId)
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

    private fun HostConnectionState.applyDeltas(batch: Map<DeltaKey, String>): HostConnectionState {
        var updated = this
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
        val hostId = state.value.activeHostId ?: return showError("请先连接服务器")
        val client = activeClient() ?: return showError("请先连接服务器")
        if (state.value.isLoadingThread && state.value.selectedThreadId == threadId) return
        scope.launch {
            val startedAtNanos = System.nanoTime()
            diagnosticLog.log("thread", "resume start host=$hostId thread=$threadId")
            updateHostConnection(hostId) { current ->
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
                    olderTurnCursor = cached?.olderTurnCursor,
                    activeTurnId = cached?.activeTurnId,
                    isTurnRunning = cached?.isTurnRunning == true,
                    error = null,
                )
            }
            try {
                val session = client.resumeThread(
                    threadId = threadId,
                )
                val initialHistory = loadInitialHistory(
                    client = client,
                    threadId = threadId,
                    itemCursor = session.itemsNextCursor,
                )
                updateHostConnection(hostId) { current ->
                    val existing = current.threadViews[threadId]
                    val turns = mergeTurns(
                        existing?.turns.orEmpty(),
                        initialHistory.turns,
                    )
                    val activeTurn = turns.lastOrNull { it.status == "inProgress" }
                    val view = ThreadViewState(
                        threadId = threadId,
                        session = session,
                        turns = turns,
                        olderHistoryCursor =
                            existing?.olderHistoryCursor ?: initialHistory.itemCursor,
                        olderTurnCursor =
                            existing?.olderTurnCursor ?: initialHistory.turnCursor,
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
                        olderTurnCursor = view.olderTurnCursor,
                        activeTurnId = view.activeTurnId,
                        isTurnRunning = view.isTurnRunning,
                        isLoadingThread = false,
                    ).withSelectedSession(session)
                }
                syncForegroundService()
                if (state.value.activeHostId == hostId) {
                    settingsStore.rememberConnection(hostId, threadId)
                }
                diagnosticLog.log(
                    "thread",
                    "resume complete host=$hostId thread=$threadId " +
                        "turns=${initialHistory.turns.size} " +
                        "items=${initialHistory.turns.sumOf { it.items.size }} " +
                        "durationMs=${elapsedMillis(startedAtNanos)}",
                )
            } catch (failure: Throwable) {
                logFailure(
                    category = "thread",
                    context = "resume host=$hostId thread=$threadId",
                    failure = failure,
                )
                updateHostConnection(hostId) {
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
                olderTurnCursor = null,
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
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val stateBefore = state.value.connectionState(hostId)
        val threadId = stateBefore.selectedThreadId ?: return
        val itemCursor = stateBefore.olderHistoryCursor
        val turnCursor = stateBefore.olderTurnCursor
        if (itemCursor == null && turnCursor == null) return
        if (stateBefore.isLoadingHistory) return
        scope.launch {
            val startedAtNanos = System.nanoTime()
            updateHostConnection(hostId) { it.copy(isLoadingHistory = true) }
            try {
                val viewBefore = state.value.connectionState(hostId).threadViews[threadId]
                    ?: state.value.connectionState(hostId).activeThreadView()
                val history = if (itemCursor != null) {
                    val page = client.listThreadItems(
                        threadId = threadId,
                        cursor = itemCursor,
                        limit = OLDER_HISTORY_ITEMS,
                        sortDirection = "desc",
                    )
                    mergeHistoryItemPage(
                        client = client,
                        threadId = threadId,
                        turns = viewBefore?.turns.orEmpty(),
                        turnCursor = turnCursor,
                        page = page,
                    )
                } else {
                    // Older servers can expose only turn pagination. Keep the fallback
                    // bounded; item pagination remains the preferred path.
                    val page = client.listTurns(
                        threadId = threadId,
                        cursor = turnCursor,
                        limit = OLDER_HISTORY_TURNS,
                        sortDirection = "desc",
                        itemsView = "notLoaded",
                    )
                    ThreadHistoryWindow(
                        turns = mergeTurns(viewBefore?.turns.orEmpty(), page.turns),
                        itemCursor = null,
                        turnCursor = page.nextCursor,
                    )
                }
                updateHostConnection(hostId) { current ->
                    current.withThreadView(threadId) { view ->
                        view.copy(
                            turns = history.turns,
                            olderHistoryCursor = history.itemCursor,
                            olderTurnCursor = history.turnCursor,
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
                diagnosticLog.log(
                    "history",
                    "loaded host=$hostId thread=$threadId turns=${history.turns.size} " +
                        "items=${history.turns.sumOf { it.items.size }} " +
                        "durationMs=${elapsedMillis(startedAtNanos)}",
                )
            } catch (failure: Throwable) {
                logFailure(
                    category = "history",
                    context = "load host=$hostId thread=$threadId",
                    failure = failure,
                )
                updateHostConnection(hostId) {
                    it.copy(isLoadingHistory = false, error = friendlyError(failure))
                }
            }
        }
    }

    fun startThread(cwd: String) {
        val hostId = state.value.activeHostId ?: return showError("请先连接服务器")
        val client = activeClient() ?: return showError("请先连接服务器")
        val path = cwd.trim()
        if (!path.startsWith('/')) return showError("项目路径必须是以 / 开头的远程绝对路径")
        if (!claimBusy()) return
        scope.launch {
            try {
                val snapshot = state.value.connectionState(hostId)
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
                updateHostConnection(hostId) {
                    val active = it.activeThreadView()
                    val view = ThreadViewState(
                        threadId = session.thread.id,
                        session = session,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                    it.copy(
                        threads = (it.threads + session.thread).distinctBy(CodexThread::id),
                        expandedProjectPaths = it.expandedProjectPaths + path,
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
                        olderTurnCursor = null,
                    ).withSelectedSession(session)
                }
                _state.update { it.copy(busy = false) }
                diagnosticLog.log(
                    "thread",
                    "created host=$hostId thread=${session.thread.id}",
                )
            } catch (failure: Throwable) {
                logFailure(
                    category = "thread",
                    context = "create host=$hostId",
                    failure = failure,
                )
                _state.update { it.copy(busy = false) }
                updateHostConnection(hostId) { it.copy(error = friendlyError(failure)) }
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
        val hostId = state.value.activeHostId ?: return showError("请先连接服务器")
        val current = state.value.connectionState(hostId)
        if (current.isLoadingThread) return showError("会话仍在同步，请稍候")
        val client = activeClient() ?: return showError("请先连接服务器")
        val threadId = current.selectedThreadId ?: return showError("请先选择会话")
        val session = current.selectedSession ?: return showError("会话尚未恢复")
        if (!claimSending(threadId)) return
        scope.launch {
            val snapshot = state.value.connectionState(hostId)
            val activeTurnId = snapshot.activeTurnId
            val steering = snapshot.isTurnRunning && !activeTurnId.isNullOrBlank()
            diagnosticLog.log(
                "turn",
                if (steering) {
                    "steer request host=$hostId thread=$threadId turn=$activeTurnId"
                } else {
                    "start request host=$hostId thread=$threadId images=${imageDataUrls.size}"
                },
            )
            try {
                if (steering) {
                    client.steerTurn(threadId, requireNotNull(activeTurnId), body, imageDataUrls)
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
                    updateHostConnection(hostId) {
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
                updateHostConnection(hostId) {
                    it.copy(
                        isSending = false,
                        composerEpoch = if (it.selectedThreadId == threadId) {
                            it.composerEpoch + 1
                        } else {
                            it.composerEpoch
                        },
                    )
                }
                diagnosticLog.log(
                    "turn",
                    if (steering) {
                        "steer accepted host=$hostId thread=$threadId"
                    } else {
                        "start accepted host=$hostId thread=$threadId"
                    },
                )
            } catch (failure: Throwable) {
                logFailure(
                    category = "turn",
                    context = if (steering) {
                        "steer host=$hostId thread=$threadId"
                    } else {
                        "start host=$hostId thread=$threadId"
                    },
                    failure = failure,
                )
                updateHostConnection(hostId) {
                    it.copy(isSending = false, error = friendlyError(failure))
                }
            }
        }
    }

    fun interruptTurn() {
        val client = activeClient() ?: return
        val state = state.value
        val threadId = state.selectedThreadId ?: return
        val turnId = state.activeTurnId ?: return
        scope.launch {
            runCatching { client.interruptTurn(threadId, turnId) }
                .onFailure { failure -> showError(friendlyError(failure)) }
        }
    }

    fun compactThread() {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.compactThread(threadId) }
                .onSuccess {
                    updateHostConnection(hostId) {
                        it.copy(notice = "已请求压缩会话上下文")
                    }
                }
                .onFailure { failure -> showHostError(hostId, friendlyError(failure)) }
        }
    }

    fun reviewUncommittedChanges() {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching {
                client.startReview(
                    threadId = threadId,
                    target = buildJsonObject { put("type", "uncommittedChanges") },
                )
            }.onSuccess { turn ->
                updateHostConnection(hostId) { current ->
                    current.withThreadView(threadId) { view ->
                        view.copy(
                            turns = trimTurns(upsertTurn(view.turns, turn)),
                            activeTurnId = turn.id,
                            isTurnRunning = turn.status == "inProgress",
                            lastUsedAt = System.currentTimeMillis(),
                        )
                    }
                }
            }.onFailure { failure -> showHostError(hostId, friendlyError(failure)) }
        }
    }

    fun forkThread() {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.forkThread(threadId) }
                .onSuccess { session ->
                    updateHostConnection(hostId) {
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
                .onFailure { failure -> showHostError(hostId, friendlyError(failure)) }
        }
    }

    fun archiveSelectedThread() {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.archiveThread(threadId) }
                .onSuccess {
                    updateHostConnection(hostId) {
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
                .onFailure { failure -> showHostError(hostId, friendlyError(failure)) }
        }
    }

    fun unarchiveSelectedThread() {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val threadId = state.value.selectedThreadId ?: return
        scope.launch {
            runCatching { client.unarchiveThread(threadId) }
                .onSuccess { thread ->
                    updateHostConnection(hostId) {
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
                .onFailure { failure -> showHostError(hostId, friendlyError(failure)) }
        }
    }

    fun renameSelectedThread(name: String) {
        val client = activeClient() ?: return
        val hostId = state.value.activeHostId ?: return
        val threadId = state.value.selectedThreadId ?: return
        if (name.isBlank()) return
        scope.launch {
            runCatching { client.renameThread(threadId, name.trim()) }
                .onSuccess { refreshThreadsNow(hostId) }
                .onFailure { failure -> showHostError(hostId, friendlyError(failure)) }
        }
    }

    fun respondToApproval(
        request: ApprovalRequest,
        decision: String,
        answers: Map<String, List<String>> = emptyMap(),
    ) {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val key = request.approvalKey()
        if (key in state.value.connectionState(hostId).respondingApprovalIds) return
        updateHostConnection(hostId) {
            it.copy(respondingApprovalIds = it.respondingApprovalIds + key)
        }
        scope.launch {
            try {
                client.approve(request, decision, answers)
                removeApproval(hostId, request)
                diagnosticLog.log(
                    "approval",
                    "responded host=$hostId request=$key method=${request.method} decision=$decision",
                )
            } catch (failure: Throwable) {
                logFailure(
                    category = "approval",
                    context = "respond host=$hostId request=$key method=${request.method}",
                    failure = failure,
                )
                updateHostConnection(hostId) {
                    it.copy(respondingApprovalIds = it.respondingApprovalIds - key)
                }
                showHostError(hostId, friendlyError(failure))
            }
        }
    }

    fun rejectApproval(request: ApprovalRequest) {
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
        val key = request.approvalKey()
        if (key in state.value.connectionState(hostId).respondingApprovalIds) return
        updateHostConnection(hostId) {
            it.copy(respondingApprovalIds = it.respondingApprovalIds + key)
        }
        scope.launch {
            try {
                if (request.kind == ApprovalKind.UNKNOWN) {
                    client.rejectServerRequest(request, "Unsupported server request")
                } else {
                    client.approve(request, decision = "decline")
                }
                removeApproval(hostId, request)
                diagnosticLog.log(
                    "approval",
                    "declined host=$hostId request=$key method=${request.method}",
                )
            } catch (failure: Throwable) {
                logFailure(
                    category = "approval",
                    context = "reject host=$hostId request=$key method=${request.method}",
                    failure = failure,
                )
                updateHostConnection(hostId) {
                    it.copy(respondingApprovalIds = it.respondingApprovalIds - key)
                }
                showHostError(hostId, friendlyError(failure))
            }
        }
    }

    private fun removeApproval(hostId: String, request: ApprovalRequest) {
        updateHostConnection(hostId) { state ->
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
                val runtime = runtimes[hostId]
                if (runtime?.client != null ||
                    runtime?.transport != null ||
                    runtime?.connectJob?.isActive == true ||
                    runtime?.reconnectJob?.isActive == true
                ) {
                    disconnect(hostId)
                }
                local.deleteHost(hostId)
                runtimes.remove(hostId)
                _state.update { current ->
                    val cleaned = current.copy(hostConnections = current.hostConnections - hostId)
                    if (current.activeHostId != hostId) {
                        cleaned
                    } else {
                        val replacement = cleaned.hostConnections.keys.firstOrNull()
                        if (replacement == null) {
                            cleaned.copy(activeHostId = null)
                        } else {
                            cleaned.copy(activeHostId = null).activateHost(replacement)
                        }
                    }
                }
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
        val hostId = state.value.activeHostId ?: return
        val client = activeClient() ?: return
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
            updateHostConnection(hostId) {
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
        val previous = state.value.settings
        settingsStore.write(settings)
        diagnosticLog.setEnabled(settings.diagnosticLoggingEnabled)
        if (!previous.diagnosticLoggingEnabled && settings.diagnosticLoggingEnabled) {
            diagnosticLog.log(
                "settings",
                "diagnostic logging enabled version=${BuildConfig.VERSION_NAME} " +
                    "revision=${BuildConfig.SOURCE_REVISION}",
            )
        }
        _state.update { it.copy(settings = settings) }
    }

    suspend fun readDiagnosticLog(): String = diagnosticLog.read()

    suspend fun clearDiagnosticLog() {
        diagnosticLog.clear()
    }

    fun setThreadSearch(value: String) {
        _state.update { it.copy(threadSearch = value) }
        val hostId = state.value.activeHostId ?: return
        val runtime = runtimeFor(hostId)
        if (runtime.client == null) return
        runtime.threadSearchJob?.cancel()
        runtime.threadSearchJob = scope.launch {
            if (value.isNotBlank()) delay(350)
            loadThreadPage(hostId = hostId, reset = true)
        }
    }

    fun toggleProjectExpanded(path: String) {
        val hostId = state.value.activeHostId ?: return
        updateHostConnection(hostId) { current ->
            current.copy(
                expandedProjectPaths = if (path in current.expandedProjectPaths) {
                    current.expandedProjectPaths - path
                } else {
                    current.expandedProjectPaths + path
                },
            )
        }
    }

    fun loadMoreThreads() {
        if (state.value.threadNextCursor == null) return
        state.value.activeHostId?.let { loadThreadPage(hostId = it, reset = false) }
    }

    fun toggleArchivedThreads() {
        _state.update {
            it.copy(
                showArchivedThreads = !it.showArchivedThreads,
                threads = emptyList(),
                threadNextCursor = null,
            )
        }
        state.value.activeHostId?.let { loadThreadPage(hostId = it, reset = true) }
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
    fun setSandbox(value: String) = _state.update { it.withSandboxMode(value) }
    fun setPermissionProfile(value: String?) = _state.update { it.copy(selectedPermissionProfileId = value) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun refreshThreadsNow(hostId: String) {
        loadThreadPage(hostId = hostId, reset = true)
    }

    private fun loadThreadPage(hostId: String, reset: Boolean) {
        val runtime = runtimeFor(hostId)
        val client = runtime.client ?: return
        runtime.threadListJob?.cancel()
        val snapshot = state.value.connectionState(hostId)
        if (!reset && snapshot.isLoadingThreads) return
        val cursor = if (reset) null else snapshot.threadNextCursor ?: return
        runtime.threadListJob = scope.launch {
            updateHostConnection(hostId) { it.copy(isLoadingThreads = true) }
            try {
                val page = client.listThreadsPage(
                    archived = snapshot.showArchivedThreads,
                    searchTerm = snapshot.threadSearch,
                    cursor = cursor,
                    limit = 60,
                )
                val latest = state.value.connectionState(hostId)
                if (latest.threadSearch != snapshot.threadSearch ||
                    latest.showArchivedThreads != snapshot.showArchivedThreads
                ) {
                    return@launch
                }
                if (!snapshot.showArchivedThreads && snapshot.threadSearch.isBlank()) {
                    local.cacheThreads(hostId, page.threads)
                }
                updateHostConnection(hostId) { state ->
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
                updateHostConnection(hostId) {
                    it.copy(isLoadingThreads = false, error = friendlyError(failure))
                }
            }
        }
    }

    private fun scheduleThreadRefresh(hostId: String) {
        val runtime = runtimeFor(hostId)
        runtime.threadRefreshJob?.cancel()
        runtime.threadRefreshJob = scope.launch {
            delay(350)
            refreshThreadsNow(hostId)
        }
    }

    private fun scheduleReconnect(hostId: String?) {
        if (hostId.isNullOrBlank()) return
        val runtime = runtimeFor(hostId)
        if (runtime.userDisconnected || runtime.reconnectJob?.isActive == true) return
        diagnosticLog.log("connection", "reconnect scheduled host=$hostId attempts=3")
        runtime.reconnectJob = scope.launch {
            val selectedThread = state.value.connectionState(hostId).selectedThreadId
            val delays = longArrayOf(2_000, 5_000, 10_000)
            for ((index, waitMs) in delays.withIndex()) {
                updateHostConnection(hostId) {
                    it.copy(
                        connectionPhase = ConnectionPhase.RECONNECTING,
                        connectionMessage = "连接中断，${waitMs / 1000} 秒后重试（${index + 1}/${delays.size}）",
                    )
                }
                syncForegroundService()
                delay(waitMs)
                try {
                    diagnosticLog.log(
                        "connection",
                        "reconnect attempt host=$hostId attempt=${index + 1}",
                    )
                    connectInternal(hostId, reconnect = true, reopenThreadId = selectedThread)
                    if (state.value.connectionState(hostId).connectionPhase ==
                        ConnectionPhase.CONNECTED
                    ) {
                        return@launch
                    }
                } catch (_: Throwable) {
                    // connectInternal records the user-facing error.
                }
            }
            updateHostConnection(hostId) {
                it.copy(
                    connectionPhase = ConnectionPhase.ERROR,
                    connectionMessage = "无法恢复连接；Agent 的实际状态需要在服务器上确认",
                )
            }
            diagnosticLog.log("connection", "reconnect exhausted host=$hostId")
            syncForegroundService()
        }
    }

    private fun closeHostConnection(hostId: String) {
        val runtime = runtimes[hostId] ?: return
        runtime.generation.incrementAndGet()
        runtime.eventJob?.cancel()
        runtime.eventJob = null
        runtime.diagnosticJob?.cancel()
        runtime.diagnosticJob = null
        runtime.threadRefreshJob?.cancel()
        runtime.threadRefreshJob = null
        runtime.threadSearchJob?.cancel()
        runtime.threadSearchJob = null
        runtime.threadListJob?.cancel()
        runtime.threadListJob = null
        val closingClient = runtime.client
        val closingTransport = runtime.transport
        val closingTunnel = runtime.localTunnel
        runtime.client = null
        runtime.transport = null
        runtime.localTunnel = null
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

    private fun collaborationModePayload(state: HostConnectionState): JsonObject? {
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

    private fun logRpcTrace(hostId: String, event: RpcTraceEvent) {
        val details = buildString {
            append("host=")
            append(hostId)
            append(" phase=")
            append(event.phase)
            append(" method=")
            append(event.method)
            event.requestId?.let {
                append(" request=")
                append(it)
            }
            event.durationMs?.let {
                append(" durationMs=")
                append(it)
            }
            event.errorCode?.let {
                append(" errorCode=")
                append(it)
            }
            event.errorMessage?.takeIf(String::isNotBlank)?.let {
                append(" error=")
                append(it)
            }
        }
        diagnosticLog.log("rpc", details)
    }

    private fun logFailure(category: String, context: String, failure: Throwable) {
        diagnosticLog.log(category, "$context failure=${throwableSummary(failure)}")
    }

    private fun throwableSummary(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .take(8)
            .joinToString(" <- ") { error ->
                buildString {
                    append(error.javaClass.simpleName)
                    (error as? RpcException)?.code?.let {
                        append(" code=")
                        append(it)
                    }
                    append(": ")
                    append(error.message.orEmpty().ifBlank { "(no message)" })
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
        runtimes.values.forEach { it.userDisconnected = true }
        runtimes.keys.toList().forEach(::closeHostConnection)
        ConnectionForegroundService.stop(appContext)
        val terminals = _state.value.terminalSessions
        val sshConnections = ssh.detachAllConnections()
        cleanupScope.launch {
            terminals.forEach { runCatching { it.controller.close() } }
            sshConnections.forEach { runCatching { it.close() } }
        }
        if (Thread.getDefaultUncaughtExceptionHandler() === uncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
        }
        diagnosticLog.close()
        scope.cancel()
        ssh.close()
        prompts.cancelAll()
    }

    private fun syncForegroundService() {
        val current = state.value
        val connectionStates = current.hostConnections.values.toMutableList()
        current.captureActiveHostConnection()?.let { activeConnection ->
            connectionStates.removeAll { it.hostId == activeConnection.hostId }
            connectionStates += activeConnection
        }
        val connectionActive = connectionStates.any {
            it.connectionPhase in setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.CONNECTED,
                ConnectionPhase.RECONNECTING,
            )
        }
        val active = connectionActive || current.terminalSessions.isNotEmpty()
        if (!active) {
            ConnectionForegroundService.stop(appContext)
            return
        }
        val runningConnection = connectionStates.firstOrNull {
            it.isTurnRunning || it.threadViews.values.any(ThreadViewState::isTurnRunning)
        }
        val title = when {
            runningConnection != null -> "Codex Turn 正在执行"
            current.terminalSessions.isNotEmpty() -> "远程终端已连接"
            connectionStates.any { it.connectionPhase == ConnectionPhase.CONNECTED } ->
                "Codex 远程连接已建立"
            else -> "CodexDeck 正在连接"
        }
        val detail = when {
            runningConnection?.isTurnRunning == true ->
                runningConnection.selectedThread?.title ?: runningConnection.connectionMessage
            runningConnection != null -> {
                val runningView = runningConnection.threadViews.values.firstOrNull {
                    it.isTurnRunning
                }
                runningConnection.threads.firstOrNull { it.id == runningView?.threadId }?.title
                    ?: runningConnection.connectionMessage
            }
            current.terminalSessions.isNotEmpty() ->
                "${current.terminalSessions.size} 个终端"
            else -> {
                val connected = connectionStates.filter { it.connectionPhase == ConnectionPhase.CONNECTED }
                "${connected.size} 台服务器已连接"
            }
        }
        ConnectionForegroundService.start(appContext, title, detail)
    }
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

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

private data class ThreadHistoryWindow(
    val turns: List<ThreadTurn>,
    val itemCursor: String?,
    val turnCursor: String?,
)

private suspend fun loadInitialHistory(
    client: AppServerClient,
    threadId: String,
    itemCursor: String?,
): ThreadHistoryWindow {
    val turnPage = client.listTurns(
        threadId = threadId,
        limit = INITIAL_HISTORY_TURNS,
        sortDirection = "desc",
        itemsView = "notLoaded",
    )
    val itemPage = client.listThreadItems(
        threadId = threadId,
        cursor = itemCursor,
        limit = INITIAL_HISTORY_ITEMS,
        sortDirection = "desc",
    )
    return mergeHistoryItemPage(
        client = client,
        threadId = threadId,
        turns = turnPage.turns,
        turnCursor = turnPage.nextCursor,
        page = itemPage,
    )
}

private suspend fun mergeHistoryItemPage(
    client: AppServerClient,
    threadId: String,
    turns: List<ThreadTurn>,
    turnCursor: String?,
    page: cc.cherr.codexdeck.protocol.ThreadItemPage,
): ThreadHistoryWindow {
    val mergedTurns = turns.associateByTo(linkedMapOf(), ThreadTurn::id)
    val missingTurnIds = page.entries.mapTo(linkedSetOf(), ThreadItemEntry::turnId)
        .apply { removeAll(mergedTurns.keys) }
    val seenTurnCursors = mutableSetOf<String>()
    var nextTurnCursor = turnCursor
    while (missingTurnIds.isNotEmpty()) {
        val cursor = nextTurnCursor?.takeIf(seenTurnCursors::add) ?: break
        val missingPage = client.listTurns(
            threadId = threadId,
            cursor = cursor,
            limit = missingTurnIds.size.coerceIn(1, 100),
            sortDirection = "desc",
            itemsView = "notLoaded",
        )
        missingPage.turns.forEach { turn ->
            mergedTurns[turn.id] = turn
            missingTurnIds.remove(turn.id)
        }
        nextTurnCursor = missingPage.nextCursor
        if (missingPage.turns.isEmpty()) break
    }

    page.entries.groupBy(ThreadItemEntry::turnId).forEach { (turnId, entries) ->
        val turn = mergedTurns[turnId] ?: return@forEach
        val existingIds = turn.items.mapTo(mutableSetOf(), ThreadItem::id)
        val olderEntries = entries.asReversed().map(ThreadItemEntry::item)
            .filter { existingIds.add(it.id) }
        if (olderEntries.isNotEmpty()) {
            mergedTurns[turnId] = turn.copy(items = olderEntries + turn.items)
        }
    }

    return ThreadHistoryWindow(
        turns = trimTurns(mergedTurns.values.sortedBy { it.startedAt ?: Long.MAX_VALUE }),
        itemCursor = page.nextCursor.takeIf { page.entries.isNotEmpty() },
        turnCursor = nextTurnCursor,
    )
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
        olderTurnCursor = olderTurnCursor,
        activeTurnId = activeTurnId,
        isTurnRunning = isTurnRunning,
        lastUsedAt = threadViews[id]?.lastUsedAt ?: System.currentTimeMillis(),
    )
}

private fun HostConnectionState.activeThreadView(): ThreadViewState? {
    val id = selectedThreadId ?: return null
    return ThreadViewState(
        threadId = id,
        session = selectedSession,
        turns = turns,
        olderHistoryCursor = olderHistoryCursor,
        olderTurnCursor = olderTurnCursor,
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
            olderTurnCursor = updated.olderTurnCursor,
            activeTurnId = updated.activeTurnId,
            isTurnRunning = updated.isTurnRunning,
        )
    }
}

private fun HostConnectionState.withThreadView(
    threadId: String?,
    transform: (ThreadViewState) -> ThreadViewState,
): HostConnectionState {
    val id = threadId ?: selectedThreadId ?: return this
    val active = selectedThreadId == id
    val current = if (active) {
        ThreadViewState(
            threadId = id,
            session = selectedSession,
            turns = turns,
            olderHistoryCursor = olderHistoryCursor,
            olderTurnCursor = olderTurnCursor,
            activeTurnId = activeTurnId,
            isTurnRunning = isTurnRunning,
            lastUsedAt = threadViews[id]?.lastUsedAt ?: System.currentTimeMillis(),
        )
    } else {
        threadViews[id]
    } ?: return this
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
            olderTurnCursor = updated.olderTurnCursor,
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

private fun HostConnectionState.withSelectedSession(session: ThreadSession): HostConnectionState = copy(
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

private fun HostConnectionState.withSelectedSettings(
    settings: ThreadSettingsSnapshot,
): HostConnectionState = copy(
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

internal fun AppState.withSandboxMode(mode: String): AppState = copy(
    sandbox = mode,
    sandboxPolicyValue = sandboxPolicy(mode),
    selectedPermissionProfileId = null,
)

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
