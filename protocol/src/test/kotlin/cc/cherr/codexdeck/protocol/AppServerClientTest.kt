package cc.cherr.codexdeck.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerClientTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun requestIdsPreserveNumericAndStringWireTypes() {
        assertEquals(RequestId.Number(1), RequestId.fromJson(JsonPrimitive(1)))
        assertEquals(RequestId.Text("1"), RequestId.fromJson(JsonPrimitive("1")))
    }

    @Test
    fun initializeUsesOmahaCompatibleEnvelopeAndPaginatesThreads() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )

        val initialize = async { client.initialize() }
        val initializeRequest = transport.receiveRequest()
        assertEquals("initialize", initializeRequest.method)
        assertFalse(initializeRequest.raw.containsKey("jsonrpc"))
        transport.emit(buildJsonObject {
            put("id", initializeRequest.id)
            put("result", buildJsonObject {
                put("userAgent", "codex-test")
                put("codexHome", "/home/dev/.codex")
                put("platformFamily", "unix")
                put("platformOs", "linux")
            })
        })
        assertEquals("codex-test", initialize.await().userAgent)

        val initialized = transport.receiveNotification()
        assertEquals("initialized", initialized.method)

        val threads = async { client.listThreads() }
        val firstPage = transport.receiveRequest()
        assertEquals("thread/list", firstPage.method)
        transport.emit(buildJsonObject {
            put("id", firstPage.id)
            put("result", buildJsonObject {
                put("data", buildJsonArray { add(threadJson("thread-1", "First")) })
                put("nextCursor", "page-2")
            })
        })
        val secondPage = transport.receiveRequest()
        assertEquals("page-2", secondPage.params["cursor"]?.jsonPrimitive?.content)
        transport.emit(buildJsonObject {
            put("id", secondPage.id)
            put("result", buildJsonObject {
                put("data", buildJsonArray {
                    add(threadJson("thread-1", "Duplicate"))
                    add(threadJson("thread-2", "Second"))
                })
                put("nextCursor", kotlinx.serialization.json.JsonNull)
            })
        })

        assertEquals(listOf("thread-1", "thread-2"), threads.await().map { it.id })
        client.close()
    }

    @Test
    fun threadListPageDeduplicatesRepeatedIdsAndKeepsActiveRuntime() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val page = async { client.listThreadsPage(limit = 60) }
        val request = transport.receiveRequest()
        transport.emit(
            ok(
                request.id,
                buildJsonObject {
                    put("data", buildJsonArray {
                        add(threadJson("thread-1", "Newer metadata", status = "idle", updatedAt = 20))
                        add(threadJson("thread-1", "Older active", status = "active", updatedAt = 10))
                        add(threadJson("thread-1", "Newest metadata", status = "idle", updatedAt = 30))
                    })
                    put("nextCursor", kotlinx.serialization.json.JsonNull)
                },
            ),
        )

        val visible = page.await().threads
        assertEquals(1, visible.size)
        assertEquals("Newest metadata", visible.single().title)
        assertEquals("active", visible.single().status.type)
        client.close()
    }

    @Test
    fun threadListPageReadsUserVisibilityMetadata() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val page = async { client.listThreadsPage(limit = 60) }
        val request = transport.receiveRequest()
        transport.emit(
            ok(
                request.id,
                buildJsonObject {
                    put("data", buildJsonArray {
                        add(
                            threadJson(
                                id = "user",
                                title = "User",
                                threadSource = "user",
                            ),
                        )
                        add(
                            threadJson(
                                id = "agent",
                                title = "Agent",
                                threadSource = "subagent",
                                agentNickname = "worker",
                                agentRole = "reviewer",
                            ),
                        )
                    })
                    put("nextCursor", kotlinx.serialization.json.JsonNull)
                },
            ),
        )

        val threads = page.await().threads
        assertTrue(threads.single { it.id == "user" }.isUserVisible)
        assertFalse(threads.single { it.id == "agent" }.isUserVisible)
        client.close()
    }

    @Test
    fun approvalResponseEchoesServerRequestIdAndDecision() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        val initializeRequest = transport.receiveRequest()
        transport.emit(ok(initializeRequest.id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val event = async {
            client.events.first { it is CodexEvent.ApprovalRequested } as CodexEvent.ApprovalRequested
        }
        transport.emit(buildJsonObject {
            put("id", 77)
            put("method", "item/commandExecution/requestApproval")
            put("params", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "item-1")
                put("command", "rm -rf build")
                put("startedAtMs", 1)
            })
        })
        val approval = event.await().request
        assertEquals(ApprovalKind.COMMAND, approval.kind)
        assertEquals("rm -rf build", approval.detail)

        client.approve(approval, "acceptForSession")
        val response = transport.receiveResponse()
        assertEquals(77L, response.id)
        assertEquals("acceptForSession", response.raw["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content)
        client.close()
    }

    @Test
    fun rpcTraceReportsMethodOutcomeAndServerErrorWithoutPayloads() = runTest {
        val transport = FakeTransport()
        val traces = mutableListOf<RpcTraceEvent>()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
            onTrace = traces::add,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val listing = async { runCatching { client.listThreadsPage(limit = 1) } }
        val request = transport.receiveRequest()
        transport.emit(
            buildJsonObject {
                put("id", request.id)
                put("error", buildJsonObject {
                    put("code", -32000)
                    put("message", "Thread already has an active writer")
                })
            },
        )
        val failure = listing.await().exceptionOrNull()
        assertTrue(failure is RpcException)

        val failed = traces.single {
            it.phase == RpcTracePhase.FAILED && it.method == "thread/list"
        }
        assertEquals(-32000, failed.errorCode)
        assertEquals("Thread already has an active writer", failed.errorMessage)
        assertTrue(traces.none { it.method != "initialize" && it.method != "thread/list" })
        client.close()
    }

    @Test
    fun parsesThreadTurnAndToolItemsWithoutFailOnUnknownFields() = runTest {
        val thread = AppServerClient.parseThread(threadJson("thread-1", "Test"))
        assertTrue(thread != null)
        assertEquals("Test", thread?.title)
        assertEquals("/repo", thread?.cwd)

        val item = AppServerClient.parseItem(buildJsonObject {
            put("id", "item-1")
            put("type", "commandExecution")
            put("status", "completed")
            put("command", "pwd")
            put("exitCode", 0)
            put("newFutureField", true)
        })
        assertEquals("commandExecution", item?.type)
        assertEquals("pwd", item?.command)
        assertEquals(0, item?.exitCode)
    }

    @Test
    fun startTurnSendsInlineImagesAsV2UserInput() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val started = async {
            client.startTurn(
                threadId = "thread-1",
                text = "",
                imageDataUrls = listOf("data:image/png;base64,AAA"),
                options = TurnOptions(
                    sandboxPolicy = buildJsonObject { put("type", "dangerFullAccess") },
                    permissionProfile = ":workspace",
                ),
            )
        }
        val request = transport.receiveRequest()
        assertEquals(":workspace", request.params["permissions"]?.jsonPrimitive?.content)
        assertFalse(request.params.containsKey("sandboxPolicy"))
        val input = request.params["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("image", input[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/png;base64,AAA",
            input[0].jsonObject["url"]?.jsonPrimitive?.content,
        )
        transport.emit(ok(request.id, buildJsonObject {
            put("turn", buildJsonObject {
                put("id", "turn-1")
                put("status", "inProgress")
                put("items", buildJsonArray {})
            })
        }))
        assertEquals("turn-1", started.await().id)
        client.close()
    }

    @Test
    fun resumeUsesNamedPermissionsInsteadOfLegacySandboxWhenBothAreProvided() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val resumed = async {
            client.resumeThread(
                threadId = "thread-1",
                sandbox = "danger-full-access",
                permissionProfile = ":workspace",
            )
        }
        val request = transport.receiveRequest()
        assertEquals(":workspace", request.params["permissions"]?.jsonPrimitive?.content)
        assertFalse(request.params.containsKey("sandbox"))
        transport.emit(
            ok(
                request.id,
                buildJsonObject {
                    put("thread", threadJson("thread-1", "Test"))
                    put("model", "test-model")
                    put("modelProvider", "test-provider")
                    put("cwd", "/repo")
                    put("approvalPolicy", "on-request")
                    put("approvalsReviewer", "user")
                    put("sandbox", buildJsonObject { put("type", "workspaceWrite") })
                },
            ),
        )
        resumed.await()
        client.close()
    }

    @Test
    fun turnHistoryUsesSummaryMetadataAndPaginatedItemDetails() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val turns = async {
            client.listTurns(threadId = "thread-1", limit = 10, sortDirection = "desc")
        }
        val turnsRequest = transport.receiveRequest()
        assertEquals("thread/turns/list", turnsRequest.method)
        assertEquals("summary", turnsRequest.params["itemsView"]?.jsonPrimitive?.content)
        assertEquals(10, turnsRequest.params["limit"]?.jsonPrimitive?.int)
        transport.emit(
            ok(
                turnsRequest.id,
                buildJsonObject {
                    put("data", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "turn-1")
                            put("status", "completed")
                            put("items", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "summary-item")
                                    put("type", "agentMessage")
                                })
                            })
                        })
                    })
                    put("nextCursor", kotlinx.serialization.json.JsonNull)
                },
            ),
        )
        assertEquals("summary-item", turns.await().turns.single().items.single().id)

        val itemDetails = async {
            client.listThreadItems(
                threadId = "thread-1",
                limit = 25,
                sortDirection = "desc",
            )
        }
        val firstItemsRequest = transport.receiveRequest()
        assertEquals("thread/items/list", firstItemsRequest.method)
        assertFalse(firstItemsRequest.params.containsKey("turnId"))
        assertEquals("desc", firstItemsRequest.params["sortDirection"]?.jsonPrimitive?.content)
        assertEquals(25, firstItemsRequest.params["limit"]?.jsonPrimitive?.int)
        transport.emit(
            ok(
                firstItemsRequest.id,
                buildJsonObject {
                    put("data", buildJsonArray {
                        add(itemEntry("item-1"))
                    })
                    put("nextCursor", "items-page-2")
                },
            ),
        )
        val itemsPage = itemDetails.await()
        assertEquals(listOf("item-1"), itemsPage.items.map { it.id })
        assertEquals(listOf("turn-1"), itemsPage.entries.map { it.turnId })
        assertEquals("items-page-2", itemsPage.nextCursor)

        val nextItemPage = async {
            client.listThreadItems(
                threadId = "thread-1",
                cursor = itemsPage.nextCursor,
                limit = 25,
                sortDirection = "desc",
            )
        }
        val nextItemsRequest = transport.receiveRequest()
        assertEquals("items-page-2", nextItemsRequest.params["cursor"]?.jsonPrimitive?.content)
        transport.emit(
            ok(
                nextItemsRequest.id,
                buildJsonObject {
                    put("data", buildJsonArray {
                        add(itemEntry("item-2"))
                    })
                    put("nextCursor", kotlinx.serialization.json.JsonNull)
                },
            ),
        )
        assertEquals(listOf("item-2"), nextItemPage.await().items.map { it.id })
        client.close()
    }

    @Test
    fun parsesMultiClientSettingsResolutionAndTurnDiffEvents() = runTest {
        val transport = FakeTransport()
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "test"),
            scope = this,
        )
        val initialize = async { client.initialize() }
        transport.emit(ok(transport.receiveRequest().id, buildJsonObject {}))
        initialize.await()
        transport.receiveNotification()

        val settingsEvent = async {
            client.events.first { it is CodexEvent.ThreadSettingsChanged }
                as CodexEvent.ThreadSettingsChanged
        }
        transport.emit(
            buildJsonObject {
                put("method", "thread/settings/updated")
                put("params", buildJsonObject {
                    put("threadId", "thread-1")
                    put("threadSettings", buildJsonObject {
                        put("model", "gpt-test")
                        put("modelProvider", "provider")
                        put("effort", "high")
                        put("cwd", "/repo")
                        put("approvalPolicy", buildJsonObject {
                            put("granular", buildJsonObject {
                                put("sandbox_approval", true)
                                put("rules", true)
                                put("mcp_elicitations", false)
                            })
                        })
                        put("approvalsReviewer", "user")
                        put("sandboxPolicy", buildJsonObject {
                            put("type", "workspaceWrite")
                            put("networkAccess", true)
                        })
                        put("activePermissionProfile", buildJsonObject { put("id", "dev") })
                        put("collaborationMode", buildJsonObject {
                            put("mode", "plan")
                            put("settings", buildJsonObject { put("model", "gpt-test") })
                        })
                    })
                })
            },
        )
        val settings = settingsEvent.await().settings
        assertEquals("gpt-test", settings.model)
        assertEquals("workspace-write", settings.sandboxMode)
        assertEquals(
            "true",
            (settings.sandboxPolicy?.get("networkAccess") as JsonPrimitive).content,
        )
        assertTrue((settings.approvalPolicy as JsonObject).containsKey("granular"))
        assertEquals("plan", settings.collaborationMode)

        val resolvedEvent = async {
            client.events.first { it is CodexEvent.ApprovalResolved }
                as CodexEvent.ApprovalResolved
        }
        transport.emit(
            buildJsonObject {
                put("method", "serverRequest/resolved")
                put("params", buildJsonObject {
                    put("threadId", "thread-1")
                    put("requestId", 77)
                })
            },
        )
        assertEquals(RequestId.Number(77), resolvedEvent.await().requestId)

        val diffEvent = async {
            client.events.first { it is CodexEvent.TurnDiffChanged }
                as CodexEvent.TurnDiffChanged
        }
        transport.emit(
            buildJsonObject {
                put("method", "turn/diff/updated")
                put("params", buildJsonObject {
                    put("threadId", "thread-1")
                    put("turnId", "turn-1")
                    put("diff", "diff --git a/a b/a")
                })
            },
        )
        assertEquals("turn-1", diffEvent.await().turnId)

        val retryEvent = async {
            client.events.first { it is CodexEvent.ServerError }
                as CodexEvent.ServerError
        }
        transport.emit(
            buildJsonObject {
                put("method", "error")
                put("params", buildJsonObject {
                    put("threadId", "thread-1")
                    put("turnId", "turn-1")
                    put("willRetry", true)
                    put("error", buildJsonObject {
                        put("message", "temporary failure")
                        put("codexErrorInfo", "other")
                    })
                })
            },
        )
        assertTrue(retryEvent.await().willRetry)
        client.close()
    }

    private fun threadJson(
        id: String,
        title: String,
        status: String = "idle",
        updatedAt: Long = 2,
        threadSource: String? = null,
        agentNickname: String? = null,
        agentRole: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("sessionId", id)
        put("name", title)
        put("preview", "$title preview")
        put("cwd", "/repo")
        put("createdAt", 1)
        put("updatedAt", updatedAt)
        put("status", buildJsonObject { put("type", status) })
        put("ephemeral", false)
        threadSource?.let { put("threadSource", it) }
        agentNickname?.let { put("agentNickname", it) }
        agentRole?.let { put("agentRole", it) }
    }

    private fun itemEntry(id: String): JsonObject = buildJsonObject {
        put("turnId", "turn-1")
        put("item", buildJsonObject {
            put("id", id)
            put("type", "commandExecution")
            put("status", "completed")
        })
    }

    private fun ok(id: Long, result: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("result", result)
    }

    private data class SeenRequest(val id: Long, val method: String, val params: JsonObject, val raw: JsonObject)
    private data class SeenNotification(val method: String, val params: JsonObject)
    private data class SeenResponse(val id: Long, val raw: JsonObject)

    private class FakeTransport : JsonLineTransport {
        private val inbound = MutableSharedFlow<String>(extraBufferCapacity = 32)
        private val outgoing = Channel<JsonObject>(Channel.UNLIMITED)
        private val parser = Json { ignoreUnknownKeys = true }

        override val incoming: Flow<String> = inbound

        override suspend fun send(line: String) {
            outgoing.send(parser.parseToJsonElement(line).jsonObject)
        }

        suspend fun emit(message: JsonObject) {
            inbound.emit(parser.encodeToString(JsonObject.serializer(), message))
        }

        suspend fun receiveRequest(): SeenRequest {
            while (true) {
                val raw = outgoing.receive()
                if (raw.containsKey("method") && raw.containsKey("id")) {
                    return SeenRequest(
                        id = raw["id"]!!.jsonPrimitive.int.toLong(),
                        method = raw["method"]!!.jsonPrimitive.content,
                        params = raw["params"]?.jsonObject ?: JsonObject(emptyMap()),
                        raw = raw,
                    )
                }
            }
        }

        suspend fun receiveNotification(): SeenNotification {
            while (true) {
                val raw = outgoing.receive()
                if (raw.containsKey("method") && !raw.containsKey("id")) {
                    return SeenNotification(
                        method = raw["method"]!!.jsonPrimitive.content,
                        params = raw["params"]?.jsonObject ?: JsonObject(emptyMap()),
                    )
                }
            }
        }

        suspend fun receiveResponse(): SeenResponse {
            while (true) {
                val raw = outgoing.receive()
                if (raw.containsKey("id") && (raw.containsKey("result") || raw.containsKey("error"))) {
                    return SeenResponse(raw["id"]!!.jsonPrimitive.int.toLong(), raw)
                }
            }
        }

        override suspend fun close() = Unit
    }
}
