package cc.cherr.codexdeck.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AppServerClient(
    private val transport: JsonLineTransport,
    private val clientInfo: ClientInfo,
    scope: CoroutineScope? = null,
    private val requestTimeout: Duration = 30.seconds,
) : Closeable {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }
    private val requestIds = AtomicLong(1)
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val eventsChannel = Channel<CodexEvent>(capacity = 512)
    private val readerStarted = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val ownsScope = scope == null
    private val clientScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    @Volatile
    private var initialized = false

    val events: Flow<CodexEvent> = eventsChannel.receiveAsFlow()

    suspend fun initialize(): InitializeResult {
        check(readerStarted.compareAndSet(false, true)) { "App-server reader has already been started" }
        readerJob = clientScope.launch { readLoop() }
        val result = requestObject(
            method = "initialize",
            params = buildJsonObject {
                put("clientInfo", buildJsonObject {
                    put("name", clientInfo.name)
                    put("title", clientInfo.title)
                    put("version", clientInfo.version)
                })
                put("capabilities", buildJsonObject {
                    put("experimentalApi", true)
                    put("requestAttestation", false)
                })
            },
            allowBeforeInitialization = true,
        )
        initialized = true
        notify("initialized")
        return InitializeResult(
            userAgent = result.string("userAgent"),
            codexHome = result.string("codexHome"),
            platformFamily = result.string("platformFamily"),
            platformOs = result.string("platformOs"),
            raw = result,
        ).also { eventsChannel.trySend(CodexEvent.Initialized(it)) }
    }

    suspend fun listThreads(
        archived: Boolean = false,
        searchTerm: String? = null,
    ): List<CodexThread> {
        val threads = linkedMapOf<String, CodexThread>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = listThreadsPage(
                archived = archived,
                searchTerm = searchTerm,
                cursor = cursor,
                limit = 100,
            )
            page.threads.forEach { threads[it.id] = it }
            cursor = page.nextCursor
            if (cursor != null && !cursors.add(cursor)) {
                throw RpcException(message = "thread/list returned a repeated pagination cursor")
            }
        } while (cursor != null)
        return threads.values.toList()
    }

    suspend fun listThreadsPage(
        archived: Boolean = false,
        searchTerm: String? = null,
        cursor: String? = null,
        limit: Int = 50,
    ): ThreadListPage {
        val response = requestObject(
            "thread/list",
            buildJsonObject {
                put("limit", limit.coerceIn(1, 100))
                put("sortKey", "updated_at")
                put("sortDirection", "desc")
                put("archived", archived)
                put("sourceKinds", buildJsonArray {})
                cursor?.takeIf(String::isNotBlank)?.let { put("cursor", it) }
                searchTerm?.trim()?.takeIf(String::isNotEmpty)?.let { put("searchTerm", it) }
            },
        )
        return ThreadListPage(
            threads = response.array("data").mapNotNull(::parseThread),
            nextCursor = response.string("nextCursor")?.takeIf(String::isNotBlank),
        )
    }

    suspend fun readThread(threadId: String): CodexThread {
        val response = requestObject("thread/read", buildJsonObject { put("threadId", threadId) })
        return response.obj("thread")?.let(::parseThread)
            ?: throw RpcException(message = "thread/read did not return a thread")
    }

    suspend fun loadedThreadIds(): List<String> {
        val response = requestObject("thread/loaded/list")
        return response.array("threadIds").mapNotNull {
            runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
        }
    }

    suspend fun resumeThread(
        threadId: String,
        approvalPolicy: JsonElement? = null,
        approvalsReviewer: String? = null,
        sandbox: String? = null,
        permissionProfile: String? = null,
    ): ThreadSession {
        val response = requestObject(
            "thread/resume",
            buildJsonObject {
                put("threadId", threadId)
                put("excludeTurns", true)
                approvalPolicy?.let { put("approvalPolicy", it) }
                approvalsReviewer?.let { put("approvalsReviewer", it) }
                if (permissionProfile.isNullOrBlank()) {
                    sandbox?.let { put("sandbox", it) }
                } else {
                    put("permissions", permissionProfile)
                }
            },
        )
        val threadObject = response.obj("thread")
            ?: throw RpcException(message = "thread/resume did not return a thread")
        val thread = parseThread(threadObject)
            ?: throw RpcException(message = "thread/resume returned an invalid thread")
        val inlineTurns = threadObject.array("turns").mapNotNull(::parseTurn)
        return ThreadSession(
            thread = thread,
            model = response.string("model"),
            modelProvider = response.string("modelProvider") ?: thread.modelProvider,
            reasoningEffort = response.string("reasoningEffort"),
            cwd = response.string("cwd") ?: thread.cwd,
            approvalPolicy = response["approvalPolicy"],
            approvalsReviewer = response.string("approvalsReviewer"),
            sandbox = response.obj("sandbox"),
            collaborationMode = response.obj("collaborationMode")?.string("mode"),
            permissionProfile = response.obj("activePermissionProfile")?.string("id"),
            turns = inlineTurns,
            turnsNextCursor = response.string("turnsBackwardsCursor"),
            raw = response,
        )
    }

    suspend fun listTurns(
        threadId: String,
        cursor: String? = null,
        limit: Int = 20,
        sortDirection: String = "desc",
    ): TurnPage {
        val response = requestObject(
            "thread/turns/list",
            buildJsonObject {
                put("threadId", threadId)
                put("limit", limit.coerceIn(1, 100))
                put("sortDirection", sortDirection)
                put("itemsView", "full")
                cursor?.let { put("cursor", it) }
            },
        )
        return TurnPage(
            turns = response.array("data").mapNotNull(::parseTurn),
            nextCursor = response.string("nextCursor"),
            backwardsCursor = response.string("backwardsCursor"),
        )
    }

    suspend fun startThread(options: ThreadStartOptions): ThreadSession {
        val response = requestObject(
            "thread/start",
            buildJsonObject {
                put("cwd", options.cwd)
                put("approvalPolicy", options.approvalPolicy)
                put("approvalsReviewer", options.approvalsReviewer)
                put("serviceName", clientInfo.name)
                if (options.permissionProfile.isNullOrBlank()) {
                    put("sandbox", options.sandbox)
                } else {
                    put("permissions", options.permissionProfile)
                }
                options.model?.takeIf(String::isNotBlank)?.let { put("model", it) }
            },
        )
        val threadObject = response.obj("thread")
            ?: throw RpcException(message = "thread/start did not return a thread")
        val thread = parseThread(threadObject)
            ?: throw RpcException(message = "thread/start returned an invalid thread")
        return ThreadSession(
            thread = thread,
            model = response.string("model"),
            modelProvider = response.string("modelProvider") ?: thread.modelProvider,
            reasoningEffort = response.string("reasoningEffort"),
            cwd = response.string("cwd") ?: options.cwd,
            approvalPolicy = response["approvalPolicy"] ?: options.approvalPolicy,
            approvalsReviewer = response.string("approvalsReviewer") ?: options.approvalsReviewer,
            sandbox = response.obj("sandbox"),
            collaborationMode = response.obj("collaborationMode")?.string("mode"),
            permissionProfile = response.obj("activePermissionProfile")?.string("id"),
            turns = emptyList(),
            turnsNextCursor = response.string("turnsBackwardsCursor"),
            raw = response,
        )
    }

    suspend fun forkThread(
        threadId: String,
        lastTurnId: String? = null,
    ): ThreadSession {
        val response = requestObject(
            "thread/fork",
            buildJsonObject {
                put("threadId", threadId)
                put("excludeTurns", true)
                lastTurnId?.let { put("lastTurnId", it) }
            },
        )
        val threadObject = response.obj("thread")
            ?: throw RpcException(message = "thread/fork did not return a thread")
        val thread = parseThread(threadObject)
            ?: throw RpcException(message = "thread/fork returned an invalid thread")
        return ThreadSession(
            thread = thread,
            model = response.string("model"),
            modelProvider = response.string("modelProvider") ?: thread.modelProvider,
            reasoningEffort = response.string("reasoningEffort"),
            cwd = response.string("cwd") ?: thread.cwd,
            approvalPolicy = response["approvalPolicy"],
            approvalsReviewer = response.string("approvalsReviewer"),
            sandbox = response.obj("sandbox"),
            collaborationMode = response.obj("collaborationMode")?.string("mode"),
            permissionProfile = response.obj("activePermissionProfile")?.string("id"),
            turns = emptyList(),
            turnsNextCursor = response.string("turnsBackwardsCursor"),
            raw = response,
        )
    }

    suspend fun startTurn(
        threadId: String,
        text: String,
        options: TurnOptions = TurnOptions(),
        imageDataUrls: List<String> = emptyList(),
    ): ThreadTurn {
        require(text.isNotBlank() || imageDataUrls.isNotEmpty()) {
            "Turn input must contain text or an image"
        }
        val response = requestObject(
            "turn/start",
            buildJsonObject {
                put("threadId", threadId)
                options.cwd?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
                options.approvalPolicy?.let { put("approvalPolicy", it) }
                options.approvalsReviewer?.let { put("approvalsReviewer", it) }
                options.model?.takeIf(String::isNotBlank)?.let { put("model", it) }
                options.reasoningEffort?.takeIf(String::isNotBlank)?.let { put("effort", it) }
                val permissionProfile = options.permissionProfile?.takeIf(String::isNotBlank)
                if (permissionProfile != null) {
                    put("permissions", permissionProfile)
                } else {
                    options.sandboxPolicy?.let { put("sandboxPolicy", it) }
                }
                options.collaborationMode?.let { put("collaborationMode", it) }
                put("input", userInput(text, imageDataUrls))
            },
            timeout = 60.seconds,
        )
        return response.obj("turn")?.let(::parseTurn)
            ?: throw RpcException(message = "turn/start did not return a turn")
    }

    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        text: String,
        imageDataUrls: List<String> = emptyList(),
    ): String {
        require(text.isNotBlank() || imageDataUrls.isNotEmpty()) {
            "Steering input must contain text or an image"
        }
        val response = requestObject(
            "turn/steer",
            buildJsonObject {
                put("threadId", threadId)
                put("expectedTurnId", expectedTurnId)
                put("input", userInput(text, imageDataUrls))
            },
        )
        return response.string("turnId") ?: expectedTurnId
    }

    suspend fun interruptTurn(threadId: String, turnId: String) {
        requestObject(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", threadId)
                put("turnId", turnId)
            },
        )
    }

    suspend fun compactThread(threadId: String) {
        requestObject("thread/compact/start", buildJsonObject { put("threadId", threadId) })
    }

    suspend fun renameThread(threadId: String, name: String) {
        requestObject(
            "thread/name/set",
            buildJsonObject {
                put("threadId", threadId)
                put("name", name)
            },
        )
    }

    suspend fun archiveThread(threadId: String) {
        requestObject("thread/archive", buildJsonObject { put("threadId", threadId) })
    }

    suspend fun unarchiveThread(threadId: String): CodexThread {
        val response = requestObject("thread/unarchive", buildJsonObject { put("threadId", threadId) })
        return response.obj("thread")?.let(::parseThread)
            ?: throw RpcException(message = "thread/unarchive did not return a thread")
    }

    suspend fun listModels(): List<ModelInfo> {
        val models = linkedMapOf<String, ModelInfo>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response = requestObject(
                "model/list",
                buildJsonObject {
                    put("limit", 100)
                    put("includeHidden", false)
                    cursor?.let { put("cursor", it) }
                },
            )
            response.array("data").mapNotNull(::parseModel).forEach { models[it.id] = it }
            cursor = response.string("nextCursor")?.takeIf(String::isNotBlank)
            if (cursor != null && !cursors.add(cursor)) {
                throw RpcException(message = "model/list returned a repeated pagination cursor")
            }
        } while (cursor != null)
        return models.values.toList()
    }

    suspend fun listCollaborationModes(): List<CollaborationMode> {
        val response = requestObject("collaborationMode/list")
        return response.array("data").mapNotNull { element ->
            val mode = element.asObject() ?: return@mapNotNull null
            val id = mode.string("mode") ?: return@mapNotNull null
            CollaborationMode(
                name = mode.string("name") ?: id,
                mode = id,
                model = mode.string("model"),
                reasoningEffort = mode.string("reasoning_effort") ?: mode.string("reasoningEffort"),
            )
        }
    }

    suspend fun listPermissionProfiles(cwd: String? = null): List<PermissionProfile> {
        val response = requestObject(
            "permissionProfile/list",
            buildJsonObject {
                put("limit", 100)
                cwd?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
            },
        )
        return response.array("data").mapNotNull { element ->
            val profile = element.asObject() ?: return@mapNotNull null
            val id = profile.string("id") ?: return@mapNotNull null
            PermissionProfile(
                id = id,
                description = profile.string("description").orEmpty(),
                allowed = profile.boolean("allowed", true),
            )
        }
    }

    suspend fun listSkills(cwd: String): List<CapabilitySkill> {
        val response = requestObject(
            "skills/list",
            buildJsonObject {
                put("cwds", buildJsonArray { add(JsonPrimitive(cwd)) })
            },
        )
        return response.array("data").flatMap { entry ->
            val item = entry.asObject() ?: return@flatMap emptyList()
            item.array("skills").mapNotNull { element ->
                val skill = element.asObject() ?: return@mapNotNull null
                val name = skill.string("name") ?: return@mapNotNull null
                CapabilitySkill(
                    name = name,
                    description = skill.string("description").orEmpty(),
                    scope = skill.string("scope").orEmpty(),
                    enabled = skill.boolean("enabled", true),
                    path = skill.string("path").orEmpty(),
                )
            }
        }
    }

    suspend fun listMcpServers(threadId: String? = null): List<CapabilityMcpServer> {
        val servers = linkedMapOf<String, CapabilityMcpServer>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response = requestObject(
                "mcpServerStatus/list",
                buildJsonObject {
                    put("limit", 100)
                    put("detail", "toolsAndAuthOnly")
                    threadId?.takeIf(String::isNotBlank)?.let { put("threadId", it) }
                    cursor?.let { put("cursor", it) }
                },
            )
            response.array("data").mapNotNull { element ->
                val server = element.asObject() ?: return@mapNotNull null
                val name = server.string("name") ?: return@mapNotNull null
                CapabilityMcpServer(
                    name = name,
                    runtimeStatus = server.string("runtimeStatus") ?: "unknown",
                    authStatus = server.string("authStatus") ?: "unknown",
                    toolCount = server.obj("tools")?.size ?: 0,
                )
            }.forEach { servers[it.name] = it }
            cursor = response.string("nextCursor")?.takeIf(String::isNotBlank)
            if (cursor != null && !cursors.add(cursor)) {
                throw RpcException(message = "mcpServerStatus/list returned a repeated cursor")
            }
        } while (cursor != null)
        return servers.values.toList()
    }

    suspend fun listPlugins(cwd: String): List<CapabilityPlugin> {
        val response = requestObject(
            "plugin/list",
            buildJsonObject {
                put("cwds", buildJsonArray { add(JsonPrimitive(cwd)) })
                put("forceRefetch", false)
            },
        )
        return response.array("marketplaces").flatMap { marketplace ->
            val item = marketplace.asObject() ?: return@flatMap emptyList()
            item.array("plugins").mapNotNull { element ->
                val plugin = element.asObject() ?: return@mapNotNull null
                val id = plugin.string("id") ?: return@mapNotNull null
                val interfaceObject = plugin.obj("interface")
                CapabilityPlugin(
                    id = id,
                    name = interfaceObject?.string("displayName")
                        ?: plugin.string("name")
                        ?: id,
                    description = interfaceObject?.string("shortDescription")
                        ?: interfaceObject?.string("longDescription").orEmpty(),
                    installed = plugin.boolean("installed"),
                    enabled = plugin.boolean("enabled"),
                    version = plugin.string("localVersion") ?: plugin.string("version"),
                )
            }
        }.distinctBy(CapabilityPlugin::id)
    }

    suspend fun startReview(
        threadId: String,
        target: JsonObject,
        delivery: String = "inline",
    ): ThreadTurn {
        val response = requestObject(
            "review/start",
            buildJsonObject {
                put("threadId", threadId)
                put("delivery", delivery)
                put("target", target)
            },
        )
        return response.obj("turn")?.let(::parseTurn)
            ?: throw RpcException(message = "review/start did not return a turn")
    }

    suspend fun requestRaw(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        requestObject(method, params)

    suspend fun approve(
        request: ApprovalRequest,
        decision: String = "accept",
        answers: Map<String, List<String>> = emptyMap(),
    ) {
        val result = when (request.kind) {
            ApprovalKind.COMMAND, ApprovalKind.FILE_CHANGE ->
                buildJsonObject { put("decision", decision) }
            ApprovalKind.PERMISSIONS -> buildJsonObject {
                put(
                    "permissions",
                    if (decision == "accept" || decision == "acceptForSession") {
                        request.params.obj("permissions") ?: buildJsonObject {}
                    } else {
                        buildJsonObject {}
                    },
                )
                put("scope", if (decision == "acceptForSession") "session" else "turn")
            }
            ApprovalKind.USER_INPUT -> buildJsonObject {
                put("answers", buildJsonObject {
                    answers.forEach { (questionId, values) ->
                        put(questionId, buildJsonObject {
                            put("answers", buildJsonArray {
                                values.forEach { add(JsonPrimitive(it)) }
                            })
                        })
                    }
                })
            }
            ApprovalKind.MCP_ELICITATION -> buildJsonObject {
                put("action", if (decision == "accept") "accept" else "decline")
                if (decision == "accept" && answers.isNotEmpty()) {
                    put("content", buildJsonObject {
                        answers.forEach { (key, values) ->
                            put(key, values.joinToString("\n"))
                        }
                    })
                }
            }
            ApprovalKind.UNKNOWN ->
                throw RpcException(message = "Unsupported server request: ${request.method}")
        }
        respond(request.requestId, result)
    }

    suspend fun rejectServerRequest(request: ApprovalRequest, message: String = "Rejected by user") {
        respondError(request.requestId, -32000, message)
    }

    suspend fun requestObject(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeout: Duration = requestTimeout,
        allowBeforeInitialization: Boolean = false,
    ): JsonObject {
        check(!closed.get()) { "App-server client is closed" }
        if (!allowBeforeInitialization) {
            check(initialized) { "App-server initialization has not completed" }
        }
        val id = requestIds.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
            return withTimeout(timeout) { response.await() }.asObjectOrEmpty()
        } catch (timeoutError: TimeoutCancellationException) {
            throw RpcException(message = "App-server request timed out: $method", cause = timeoutError)
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) {
        check(!closed.get()) { "App-server client is closed" }
        send(buildJsonObject {
            put("method", method)
            put("params", params)
        })
    }

    suspend fun respond(id: RequestId, result: JsonObject) {
        send(buildJsonObject {
            put("id", when (id) {
                is RequestId.Number -> JsonPrimitive(id.value)
                is RequestId.Text -> JsonPrimitive(id.value)
            })
            put("result", result)
        })
    }

    suspend fun respondError(id: RequestId, code: Int, message: String, data: JsonObject? = null) {
        send(buildJsonObject {
            put("id", when (id) {
                is RequestId.Number -> JsonPrimitive(id.value)
                is RequestId.Text -> JsonPrimitive(id.value)
            })
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            })
        })
    }

    private suspend fun send(message: JsonObject) {
        writeMutex.withLock {
            if (closed.get()) throw RpcException(message = "App-server client is closed")
            withContext(Dispatchers.IO) {
                transport.send(json.encodeToString(JsonObject.serializer(), message))
            }
        }
    }

    private suspend fun readLoop() {
        var failure: Throwable? = null
        try {
            transport.incoming.collect { line ->
                if (line.isBlank()) return@collect
                val message = runCatching { json.parseToJsonElement(line).asObjectOrEmpty() }.getOrNull()
                if (message == null) {
                    eventsChannel.send(CodexEvent.Diagnostic("Ignored non-JSON app-server output (${line.length} chars)"))
                    return@collect
                }
                handleMessage(message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (!closed.get()) {
                val rpcError = RpcException(message = "App-server connection closed", cause = failure)
                pending.values.forEach { it.completeExceptionally(rpcError) }
                pending.clear()
                eventsChannel.trySend(CodexEvent.Disconnected(failure))
            }
        }
    }

    private suspend fun handleMessage(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.let(RequestId::fromJson)
        val method = message.string("method")
        val hasResponseShape = message.containsKey("result") || message.containsKey("error")

        if (id is RequestId.Number && hasResponseShape) {
            val pendingRequest = pending.remove(id.value)
            val error = message.obj("error")
            if (error != null) {
                pendingRequest?.completeExceptionally(
                    RpcException(
                        code = error.int("code"),
                        serverData = error.obj("data"),
                        message = error.string("message") ?: "JSON-RPC request failed",
                    ),
                )
            } else {
                pendingRequest?.complete(message["result"] ?: JsonNull)
            }
            return
        }

        val params = message.obj("params") ?: JsonObject(emptyMap())
        if (id != null && method != null) {
            handleServerRequest(id, method, params)
            return
        }
        if (method != null) handleNotification(method, params)
    }

    private suspend fun handleServerRequest(id: RequestId, method: String, params: JsonObject) {
        val request = when (method) {
            "item/commandExecution/requestApproval", "execCommandApproval" -> ApprovalRequest(
                requestId = id,
                method = method,
                kind = ApprovalKind.COMMAND,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                title = "允许执行命令？",
                detail = params.string("command") ?: params.string("reason") ?: "远端 Codex 请求执行命令",
                params = params,
            )
            "item/fileChange/requestApproval", "applyPatchApproval" -> ApprovalRequest(
                requestId = id,
                method = method,
                kind = ApprovalKind.FILE_CHANGE,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                title = "允许修改文件？",
                detail = params.string("reason") ?: params.string("grantRoot") ?: "远端 Codex 请求写入项目",
                params = params,
            )
            "item/permissions/requestApproval" -> ApprovalRequest(
                requestId = id,
                method = method,
                kind = ApprovalKind.PERMISSIONS,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                title = "允许额外权限？",
                detail = params.string("reason") ?: "远端 Codex 请求额外文件或网络权限",
                params = params,
            )
            "item/tool/requestUserInput", "tool/requestUserInput" -> ApprovalRequest(
                requestId = id,
                method = method,
                kind = ApprovalKind.USER_INPUT,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                title = "Codex 需要你的输入",
                detail = params.string("questions")
                    ?: params.array("questions").firstOrNull()?.asObject()?.string("question")
                    ?: "请回答问题",
                params = params,
            )
            "mcpServer/elicitation/request" -> ApprovalRequest(
                requestId = id,
                method = method,
                kind = ApprovalKind.MCP_ELICITATION,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                title = "MCP 请求确认",
                detail = params.string("message") ?: "远端 MCP Server 请求用户确认",
                params = params,
            )
            else -> ApprovalRequest(
                requestId = id,
                method = method,
                kind = ApprovalKind.UNKNOWN,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                title = "远端请求",
                detail = method,
                params = params,
            )
        }
        eventsChannel.send(CodexEvent.ApprovalRequested(request))
    }

    private suspend fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "item/started", "item/completed" -> {
                val item = params.obj("item")?.let(::parseItem) ?: return
                eventsChannel.send(
                    CodexEvent.ItemUpsert(
                        threadId = params.string("threadId"),
                        turnId = params.string("turnId"),
                        item = item,
                    ),
                )
            }
            "item/fileChange/patchUpdated" -> {
                val itemId = params.string("itemId") ?: return
                val item = ThreadItem(
                    id = itemId,
                    type = "fileChange",
                    status = "inProgress",
                    raw = buildJsonObject {
                        put("id", itemId)
                        put("type", "fileChange")
                        put("status", "inProgress")
                        params["changes"]?.let { put("changes", it) }
                    },
                )
                eventsChannel.send(
                    CodexEvent.ItemUpsert(
                        threadId = params.string("threadId"),
                        turnId = params.string("turnId"),
                        item = item,
                    ),
                )
            }
            "item/agentMessage/delta" -> eventsChannel.send(
                CodexEvent.AgentDelta(
                    params.string("threadId"),
                    params.string("itemId").orEmpty(),
                    params.string("delta").orEmpty(),
                ),
            )
            "item/plan/delta" -> eventsChannel.send(
                CodexEvent.PlanDelta(
                    params.string("threadId"),
                    params.string("itemId").orEmpty(),
                    params.string("delta").orEmpty(),
                ),
            )
            "item/reasoning/summaryTextDelta" -> eventsChannel.send(
                CodexEvent.ReasoningDelta(
                    params.string("threadId"),
                    params.string("itemId").orEmpty(),
                    params.string("delta").orEmpty(),
                    summary = true,
                ),
            )
            "item/reasoning/textDelta" -> eventsChannel.send(
                CodexEvent.ReasoningDelta(
                    params.string("threadId"),
                    params.string("itemId").orEmpty(),
                    params.string("delta").orEmpty(),
                    summary = false,
                ),
            )
            "item/commandExecution/outputDelta" -> eventsChannel.send(
                CodexEvent.CommandOutputDelta(
                    params.string("threadId"),
                    params.string("itemId").orEmpty(),
                    params.string("delta").orEmpty(),
                ),
            )
            "item/commandExecution/terminalInteraction" -> {
                val stdin = params.string("stdin").orEmpty()
                if (stdin.isNotBlank()) {
                    eventsChannel.send(
                        CodexEvent.CommandOutputDelta(
                            params.string("threadId"),
                            params.string("itemId").orEmpty(),
                            "\n> ${stdin.take(20_000)}\n",
                        ),
                    )
                }
            }
            "turn/started" -> {
                val turn = params.obj("turn")
                eventsChannel.send(
                    CodexEvent.TurnChanged(
                        threadId = params.string("threadId"),
                        turnId = turn?.string("id"),
                        running = true,
                        status = turn?.string("status") ?: "inProgress",
                    ),
                )
            }
            "turn/completed" -> {
                val turn = params.obj("turn")
                eventsChannel.send(
                    CodexEvent.TurnChanged(
                        threadId = params.string("threadId"),
                        turnId = turn?.string("id"),
                        running = false,
                        status = turn?.string("status"),
                        error = turn?.obj("error")?.string("message"),
                    ),
                )
            }
            "turn/diff/updated" -> {
                val threadId = params.string("threadId")
                val turnId = params.string("turnId") ?: return
                eventsChannel.send(
                    CodexEvent.TurnDiffChanged(
                        threadId = threadId,
                        turnId = turnId,
                        diff = params.string("diff").orEmpty(),
                    ),
                )
            }
            "turn/plan/updated" -> {
                val threadId = params.string("threadId")
                val turnId = params.string("turnId") ?: return
                val itemId = "plan:$turnId"
                val lines = params.array("plan").mapNotNull { element ->
                    val step = element.asObject() ?: return@mapNotNull null
                    val text = step.string("step") ?: return@mapNotNull null
                    "- [${step.string("status").orEmpty()}] $text"
                }
                val text = buildString {
                    params.string("explanation")?.takeIf(String::isNotBlank)?.let {
                        append(it)
                        append("\n\n")
                    }
                    append(lines.joinToString("\n"))
                }
                eventsChannel.send(
                    CodexEvent.ItemUpsert(
                        threadId = threadId,
                        turnId = turnId,
                        item = ThreadItem(
                            id = itemId,
                            type = "plan",
                            status = "inProgress",
                            raw = buildJsonObject {
                                put("id", itemId)
                                put("type", "plan")
                                put("status", "inProgress")
                                put("text", text)
                                put("plan", params["plan"] ?: buildJsonArray {})
                            },
                        ),
                    ),
                )
            }
            "thread/started", "thread/closed", "thread/archived", "thread/unarchived",
            "thread/name/updated", "thread/status/changed", "thread/deleted",
            -> eventsChannel.send(CodexEvent.ThreadChanged(params.string("threadId"), method))
            "thread/settings/updated" -> {
                val settings = params.obj("threadSettings")?.let(::parseThreadSettings) ?: return
                eventsChannel.send(
                    CodexEvent.ThreadSettingsChanged(
                        threadId = params.string("threadId"),
                        settings = settings,
                    ),
                )
            }
            "serverRequest/resolved" -> {
                val requestId = (params["requestId"] as? JsonPrimitive)
                    ?.let(RequestId::fromJson)
                    ?: return
                eventsChannel.send(
                    CodexEvent.ApprovalResolved(
                        requestId = requestId,
                        threadId = params.string("threadId"),
                    ),
                )
            }
            "error" -> {
                val error = params.obj("error")
                eventsChannel.send(
                    CodexEvent.ServerError(
                        threadId = params.string("threadId"),
                        message = error?.string("message") ?: params.string("message") ?: "Codex server error",
                        code = error?.string("codexErrorInfo"),
                        willRetry = params.boolean("willRetry"),
                    ),
                )
            }
            "warning", "guardianWarning", "deprecationNotice", "configWarning" -> {
                val message = params.string("message") ?: params.obj("warning")?.string("message")
                if (!message.isNullOrBlank()) eventsChannel.send(CodexEvent.Warning(message))
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val error = RpcException(message = "App-server client closed")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        eventsChannel.close(error)
        readerJob?.cancel()
        if (ownsScope) clientScope.cancel("App-server client closed")
        kotlinx.coroutines.runBlocking { runCatching { transport.close() } }
    }

    private fun userInput(text: String, imageDataUrls: List<String>): kotlinx.serialization.json.JsonArray =
        buildJsonArray {
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    put("text_elements", buildJsonArray {})
                })
            }
            imageDataUrls.forEach { dataUrl ->
                require(dataUrl.startsWith("data:image/") && dataUrl.contains(";base64,")) {
                    "Only inline base64 image data URLs are supported"
                }
                add(buildJsonObject {
                    put("type", "image")
                    put("url", dataUrl)
                    put("detail", "high")
                })
            }
        }

    companion object {
        internal fun parseThread(element: JsonElement): CodexThread? {
            val thread = element.asObject() ?: return null
            val id = thread.string("id") ?: thread.string("sessionId") ?: return null
            val preview = thread.string("preview").orEmpty()
            val title = thread.string("name")?.takeIf(String::isNotBlank)
                ?: preview.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                ?: "Untitled thread"
            val statusObject = thread.obj("status")
            val status = ThreadRuntimeStatus(
                type = statusObject?.string("type") ?: thread.string("status") ?: "notLoaded",
                activeFlags = statusObject?.array("activeFlags").orEmpty().mapNotNull {
                    runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
                },
            )
            val gitInfo = thread.obj("gitInfo")
            return CodexThread(
                id = id,
                sessionId = thread.string("sessionId") ?: id,
                title = title,
                preview = preview,
                cwd = thread.string("cwd").orEmpty(),
                modelProvider = thread.string("modelProvider"),
                createdAt = thread.long("createdAt") ?: 0,
                updatedAt = thread.long("updatedAt") ?: thread.long("createdAt") ?: 0,
                status = status,
                ephemeral = thread.boolean("ephemeral"),
                forkedFromId = thread.string("forkedFromId"),
                gitBranch = gitInfo?.string("branch"),
                gitOriginUrl = gitInfo?.string("originUrl"),
                raw = thread,
            )
        }

        internal fun parseTurn(element: JsonElement): ThreadTurn? {
            val turn = element.asObject() ?: return null
            val id = turn.string("id") ?: return null
            val error = turn.obj("error")?.let {
                TurnError(
                    message = it.string("message").orEmpty(),
                    additionalDetails = it.string("additionalDetails"),
                    codexErrorInfo = it.string("codexErrorInfo"),
                )
            }
            return ThreadTurn(
                id = id,
                status = turn.string("status") ?: "inProgress",
                items = turn.array("items").mapNotNull(::parseItem),
                startedAt = turn.long("startedAt"),
                completedAt = turn.long("completedAt"),
                durationMs = turn.long("durationMs"),
                error = error,
                raw = turn,
            )
        }

        internal fun parseItem(element: JsonElement): ThreadItem? {
            val item = element.asObject() ?: return null
            val id = item.string("id") ?: return null
            return ThreadItem(
                id = id,
                type = item.string("type") ?: "unknown",
                status = item.string("status").orEmpty(),
                raw = item,
            )
        }

        internal fun parseThreadSettings(element: JsonElement): ThreadSettingsSnapshot? {
            val settings = element.asObject() ?: return null
            val sandbox = settings.obj("sandboxPolicy")
            return ThreadSettingsSnapshot(
                model = settings.string("model"),
                modelProvider = settings.string("modelProvider"),
                reasoningEffort = settings.string("effort")
                    ?: settings.string("reasoningEffort"),
                cwd = settings.string("cwd"),
                approvalPolicy = settings["approvalPolicy"],
                approvalsReviewer = settings.string("approvalsReviewer"),
                sandboxMode = sandboxMode(sandbox),
                sandboxPolicy = sandbox,
                permissionProfile = settings.obj("activePermissionProfile")?.string("id"),
                collaborationMode = settings.obj("collaborationMode")?.string("mode"),
            )
        }

        internal fun sandboxMode(policy: JsonObject?): String? = when (
            policy?.string("type")
        ) {
            "readOnly" -> "read-only"
            "workspaceWrite" -> "workspace-write"
            "dangerFullAccess" -> "danger-full-access"
            else -> null
        }

        internal fun parseModel(element: JsonElement): ModelInfo? {
            val model = element.asObject() ?: return null
            val id = model.string("model") ?: model.string("id") ?: return null
            return ModelInfo(
                id = id,
                displayName = model.string("displayName") ?: id,
                description = model.string("description").orEmpty(),
                isDefault = model.boolean("isDefault"),
                hidden = model.boolean("hidden"),
                defaultReasoningEffort = model.string("defaultReasoningEffort"),
                supportedReasoningEfforts = model.array("supportedReasoningEfforts").mapNotNull {
                    val option = it.asObject() ?: return@mapNotNull null
                    val effort = option.string("reasoningEffort") ?: return@mapNotNull null
                    ReasoningEffortOption(effort, option.string("description").orEmpty())
                },
                inputModalities = model.array("inputModalities").mapNotNullTo(linkedSetOf()) {
                    runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
                },
            )
        }

        internal fun JsonElement.asObjectOrEmpty(): JsonObject =
            asObject() ?: JsonObject(emptyMap())
    }
}
