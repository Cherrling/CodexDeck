package cc.cherr.codexdeck.ui

import cc.cherr.codexdeck.protocol.ThreadItem
import cc.cherr.codexdeck.protocol.ThreadTurn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
    @Test
    fun completedTurnFoldsProcessButKeepsUserAndFinalAnswer() {
        val turn = turn(
            status = "completed",
            items = listOf(
                item("user", "userMessage"),
                item("reason", "reasoning"),
                item("command", "commandExecution"),
                item("answer", "agentMessage"),
            ),
        )

        val rows = buildTimelineRows(listOf(turn))

        assertEquals(3, rows.size)
        assertEquals(TimelineMessageRole.USER, (rows[0] as TimelineRow.Message).role)
        assertTrue(rows[1] is TimelineRow.TurnSummary)
        assertEquals(TimelineMessageRole.ANSWER, (rows[2] as TimelineRow.Message).role)
    }

    @Test
    fun expandedCompletedTurnRestoresStableProcessKeysAndDiff() {
        val turn = turn(
            status = "completed",
            rawDiff = "diff --git a/a b/a",
            items = listOf(
                item("user", "userMessage"),
                item("reason", "reasoning"),
                item("command", "commandExecution"),
                item("answer", "agentMessage"),
            ),
        )

        val rows = buildTimelineRows(listOf(turn), setOf(turn.id))

        assertEquals(
            listOf(
                "item:user",
                "turn:turn-1",
                "process:turn-1:reason",
                "process:turn-1:command",
                "turn-diff:turn-1",
                "item:answer",
            ),
            rows.map(TimelineRow::key),
        )
    }

    @Test
    fun runningAndFailedTurnsKeepProcessVisible() {
        val running = turn(
            status = "inProgress",
            items = listOf(item("command", "commandExecution")),
        )
        val failed = turn(
            id = "turn-2",
            status = "failed",
            items = listOf(item("reason-2", "reasoning")),
        )

        val rows = buildTimelineRows(listOf(running, failed))

        assertTrue(rows.any { it.key == "process:${running.id}:command" })
        assertTrue(rows.any { it.key == "process:${failed.id}:reason-2" })
    }

    @Test
    fun processStatsCountOnlyProcessToolsAndExposeFailures() {
        val stats = processStats(
            listOf(
                item("reason", "reasoning"),
                item("command", "commandExecution", "failed"),
                item("file", "fileChange"),
                item("mcp", "mcpToolCall"),
                item("web", "webSearch", "declined"),
                item("message", "agentMessage"),
            ),
        )

        assertEquals(ProcessStats(toolCalls = 4, thoughts = 1, messages = 1, failed = 2), stats)
        assertEquals(
            "已完成 · 4 次工具调用 · 1 条中间消息 · 2 个失败",
            turnSummaryLabel("completed", stats),
        )
    }

    @Test
    fun reasoningUsesCompletedContentThenStreamedFallback() {
        val completed = item(
            id = "reason",
            type = "reasoning",
            raw = buildJsonObject {
                put("summary", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "summary_text")
                        put("text", "final summary")
                    })
                })
                put("content", buildJsonArray { add(JsonPrimitive("final detail")) })
                put("reasoningSummary", "stale streamed summary")
            },
        )
        val streamedOnly = item(
            id = "streamed",
            type = "reasoning",
            raw = buildJsonObject {
                put("reasoningSummary", "streamed summary")
                put("reasoningText", "streamed detail")
            },
        )

        assertEquals("final summary\n\nfinal detail", reasoningTextOf(completed))
        assertEquals("streamed summary\n\nstreamed detail", reasoningTextOf(streamedOnly))
    }

    @Test
    fun previewsAndLargeOutputsStayBounded() {
        assertEquals(
            "Inspect files",
            reasoningPreview("## **Inspect files**\nsecond line"),
        )
        assertEquals("…cdef", streamingReasoningTail("abcdef", maxChars = 4))

        val tail = boundedProcessText("abcdef", maxChars = 3, keepTail = true)
        assertTrue(tail.contains("已省略前 3 个字符"))
        assertTrue(tail.endsWith("def"))

        val head = boundedProcessText("abcdef", maxChars = 3, keepTail = false)
        assertTrue(head.startsWith("abc"))
        assertTrue(head.contains("已省略后 3 个字符"))
    }

    private fun turn(
        id: String = "turn-1",
        status: String,
        items: List<ThreadItem>,
        rawDiff: String? = null,
    ) = ThreadTurn(
        id = id,
        status = status,
        items = items,
        startedAt = 1L,
        completedAt = 2L,
        durationMs = 1L,
        error = null,
        raw = buildJsonObject {
            rawDiff?.let { put("diff", it) }
        },
    )

    private fun item(
        id: String,
        type: String,
        status: String = "completed",
        raw: kotlinx.serialization.json.JsonObject = buildJsonObject {},
    ) = ThreadItem(
        id = id,
        type = type,
        status = status,
        raw = raw,
    )
}
