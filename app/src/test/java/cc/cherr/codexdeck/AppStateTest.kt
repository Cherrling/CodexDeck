package cc.cherr.codexdeck

import cc.cherr.codexdeck.protocol.ApprovalKind
import cc.cherr.codexdeck.protocol.ApprovalRequest
import cc.cherr.codexdeck.protocol.CodexThread
import cc.cherr.codexdeck.protocol.RequestId
import cc.cherr.codexdeck.protocol.ThreadRuntimeStatus
import cc.cherr.codexdeck.protocol.ThreadTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test
    fun groupsThreadsByExactRemoteWorkingDirectory() {
        val threads = listOf(
            thread("1", "/srv/api", 10),
            thread("2", "/srv/web", 30),
            thread("3", "/srv/api", 20),
        )

        val projects = groupThreads(threads)

        assertEquals(listOf("/srv/web", "/srv/api"), projects.map { it.path })
        assertEquals(listOf("3", "1"), projects[1].threads.map { it.id })
    }

    @Test
    fun historyRetentionKeepsNewestTurns() {
        val turns = (1..150).map { turn ->
            ThreadTurn(
                id = turn.toString(),
                status = "completed",
                items = emptyList(),
                startedAt = turn.toLong(),
                completedAt = turn.toLong(),
                durationMs = 0,
                error = null,
                raw = JsonObject(emptyMap()),
            )
        }

        assertEquals((31..150).map(Int::toString), trimTurns(turns).map(ThreadTurn::id))
    }

    @Test
    fun threadViewCacheEvictsLeastRecentlyUsedInactiveSessions() {
        val views = (1..10).associate { index ->
            val id = "thread-$index"
            id to ThreadViewState(threadId = id, lastUsedAt = index.toLong())
        }

        val retained = trimThreadViews(views, activeThreadId = "thread-1")

        assertEquals(8, retained.size)
        assertEquals(setOf("1", "4", "5", "6", "7", "8", "9", "10"), retained.keys.map { it.substringAfterLast('-') }.toSet())
    }

    @Test
    fun sshTunnelRewritesOnlyTheWebSocketAuthority() {
        assertEquals(
            "ws://127.0.0.1:43123/rpc?client=android",
            tunneledWebSocketUrl("ws://127.0.0.1:8765/rpc?client=android", 43123),
        )
    }

    @Test
    fun approvalKeysKeepNumericAndTextRequestIdsDistinct() {
        fun request(id: RequestId) = ApprovalRequest(
            requestId = id,
            method = "item/commandExecution/requestApproval",
            kind = ApprovalKind.COMMAND,
            threadId = "thread",
            turnId = "turn",
            itemId = "item",
            title = "Approval",
            detail = "pwd",
            params = JsonObject(emptyMap()),
        )

        assertEquals("Number:1", request(RequestId.Number(1)).approvalKey())
        assertEquals("Text:1", request(RequestId.Text("1")).approvalKey())
    }

    @Test
    fun granularApprovalPolicyHasAnExplicitDisplayLabel() {
        assertEquals("on-request", approvalPolicyLabel(JsonPrimitive("on-request")))
        assertEquals(
            "granular",
            approvalPolicyLabel(buildJsonObject {
                put("granular", buildJsonObject {})
            }),
        )
    }

    private fun thread(id: String, cwd: String, updatedAt: Long) = CodexThread(
        id = id,
        sessionId = id,
        title = id,
        preview = "",
        cwd = cwd,
        modelProvider = null,
        createdAt = updatedAt - 1,
        updatedAt = updatedAt,
        status = ThreadRuntimeStatus("idle"),
        ephemeral = false,
        forkedFromId = null,
        gitBranch = null,
        gitOriginUrl = null,
        raw = JsonObject(emptyMap()),
    )
}
