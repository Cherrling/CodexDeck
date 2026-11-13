package cc.cherr.codexdeck.ui

import cc.cherr.codexdeck.protocol.ThreadItem
import cc.cherr.codexdeck.protocol.ThreadTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class TimelineMessageRole {
    USER,
    ANSWER,
}

internal sealed interface TimelineRow {
    val key: String

    data class Message(
        val item: ThreadItem,
        val role: TimelineMessageRole,
    ) : TimelineRow {
        override val key: String = "item:${item.id}"
    }

    data class TurnSummary(
        val turn: ThreadTurn,
        val stats: ProcessStats,
        val expanded: Boolean,
        val hasDetails: Boolean,
    ) : TimelineRow {
        override val key: String = "turn:${turn.id}"
    }

    data class ProcessEntry(
        val turnId: String,
        val turnStatus: String,
        val item: ThreadItem,
    ) : TimelineRow {
        override val key: String = "process:$turnId:${item.id}"
    }

    data class TurnDiff(
        val turnId: String,
        val diff: String,
    ) : TimelineRow {
        override val key: String = "turn-diff:$turnId"
    }
}

internal data class ProcessStats(
    val toolCalls: Int = 0,
    val thoughts: Int = 0,
    val messages: Int = 0,
    val failed: Int = 0,
)

private val toolCallTypes = setOf(
    "commandExecution",
    "fileChange",
    "mcpToolCall",
    "dynamicToolCall",
    "collabAgentToolCall",
    "webSearch",
)

internal fun buildTimelineRows(
    turns: List<ThreadTurn>,
    expandedTurnIds: Set<String> = emptySet(),
): List<TimelineRow> = turns.flatMap { turn ->
    val userMessages = turn.items.filter { it.type == "userMessage" }
    val answerIndex = turn.items.indexOfLast { it.type == "agentMessage" }
    val answer = turn.items.getOrNull(answerIndex)
    val processItems = turn.items.filterIndexed { index, item ->
        item.type != "userMessage" && index != answerIndex
    }
    val stats = processStats(processItems)
    val diff = turn.raw["diff"].asStringOrNull()?.takeIf(String::isNotBlank)
    val hasDetails = processItems.isNotEmpty() || diff != null
    val expanded = turn.id in expandedTurnIds
    val processVisible = turn.status != "completed" || expanded

    buildList {
        userMessages.forEach { add(TimelineRow.Message(it, TimelineMessageRole.USER)) }
        add(
            TimelineRow.TurnSummary(
                turn = turn,
                stats = stats,
                expanded = expanded,
                hasDetails = hasDetails,
            ),
        )
        if (processVisible) {
            processItems.forEach { add(TimelineRow.ProcessEntry(turn.id, turn.status, it)) }
            diff?.let { add(TimelineRow.TurnDiff(turn.id, it)) }
        }
        answer?.let { add(TimelineRow.Message(it, TimelineMessageRole.ANSWER)) }
    }
}

internal fun processStats(items: List<ThreadItem>): ProcessStats {
    var tools = 0
    var thoughts = 0
    var messages = 0
    var failed = 0
    items.forEach { item ->
        if (item.type in toolCallTypes) tools++
        if (item.type == "reasoning") thoughts++
        if (item.type == "agentMessage") messages++
        val status = item.status.ifBlank { item.raw["status"].asStringOrNull().orEmpty() }
        if (status == "failed" || status == "declined") failed++
    }
    return ProcessStats(
        toolCalls = tools,
        thoughts = thoughts,
        messages = messages,
        failed = failed,
    )
}

internal fun turnSummaryLabel(status: String, stats: ProcessStats): String {
    val base = when (status) {
        "completed" -> "已完成"
        "failed" -> "执行失败"
        "interrupted" -> "已中断"
        "inProgress" -> "运行中"
        else -> status.ifBlank { "执行记录" }
    }
    return buildList {
        add(base)
        if (stats.toolCalls > 0) add("${stats.toolCalls} 次工具调用")
        if (stats.messages > 0) add("${stats.messages} 条中间消息")
        if (stats.toolCalls == 0 && stats.messages == 0 && stats.thoughts > 0) {
            add("${stats.thoughts} 段思考")
        }
        if (stats.failed > 0) add("${stats.failed} 个失败")
    }.joinToString(" · ")
}

internal fun reasoningTextOf(item: ThreadItem): String {
    val finalParts = arrayContentStrings(item.raw["summary"]) +
        arrayContentStrings(item.raw["content"])
    val streamedParts = listOfNotNull(
        item.raw["reasoningSummary"].asStringOrNull(),
        item.raw["reasoningText"].asStringOrNull(),
    )
    return finalParts
        .takeIf { parts -> parts.any(String::isNotBlank) }
        .orEmpty()
        .ifEmpty { streamedParts }
        .filter(String::isNotBlank)
        .joinToString("\n\n")
}

internal fun reasoningPreview(text: String, maxChars: Int = 180): String =
    text.lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
        .replace(Regex("""^#{1,6}\s+"""), "")
        .replace(Regex("""[*_`]{1,3}"""), "")
        .trim()
        .take(maxChars)

internal fun streamingReasoningTail(text: String, maxChars: Int = 360): String {
    val trimmed = text.trim()
    if (trimmed.length <= maxChars) return trimmed
    return "…" + trimmed.takeLast(maxChars)
}

internal fun boundedProcessText(
    text: String,
    maxChars: Int = 32_000,
    keepTail: Boolean = false,
): String {
    if (text.length <= maxChars) return text
    val omitted = text.length - maxChars
    return if (keepTail) {
        "… 已省略前 $omitted 个字符 …\n\n" + text.takeLast(maxChars)
    } else {
        text.take(maxChars) + "\n\n… 已省略后 $omitted 个字符 …"
    }
}

private fun arrayContentStrings(element: JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().mapNotNull { value ->
        when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value["text"].asStringOrNull()
            else -> null
        }
    }

private fun JsonElement?.asStringOrNull(): String? =
    runCatching { (this as? JsonPrimitive)?.contentOrNull }.getOrNull()
