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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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

    private fun threadJson(id: String, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("sessionId", id)
        put("name", title)
        put("preview", "$title preview")
        put("cwd", "/repo")
        put("createdAt", 1)
        put("updatedAt", 2)
        put("status", buildJsonObject { put("type", "idle") })
        put("ephemeral", false)
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
