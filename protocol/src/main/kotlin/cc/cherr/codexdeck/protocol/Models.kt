package cc.cherr.codexdeck.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ClientInfo(
    val name: String = "codex_deck_android",
    val title: String = "CodexDeck",
    val version: String,
)

data class InitializeResult(
    val userAgent: String?,
    val codexHome: String?,
    val platformFamily: String?,
    val platformOs: String?,
    val raw: JsonObject,
)

data class CodexThread(
    val id: String,
    val sessionId: String,
    val title: String,
    val preview: String,
    val cwd: String,
    val modelProvider: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ThreadRuntimeStatus,
    val ephemeral: Boolean,
    val forkedFromId: String?,
    val gitBranch: String?,
    val gitOriginUrl: String?,
    val raw: JsonObject,
)

data class ThreadRuntimeStatus(
    val type: String,
    val activeFlags: List<String> = emptyList(),
) {
    val isActive: Boolean get() = type == "active"
    val isLoaded: Boolean get() = type != "notLoaded"
}

data class ThreadSession(
    val thread: CodexThread,
    val model: String?,
    val modelProvider: String?,
    val reasoningEffort: String?,
    val cwd: String,
    val approvalPolicy: String?,
    val approvalsReviewer: String?,
    val sandbox: JsonObject?,
    val collaborationMode: String?,
    val permissionProfile: String?,
    val turns: List<ThreadTurn>,
    val turnsNextCursor: String?,
    val raw: JsonObject,
)

data class ThreadTurn(
    val id: String,
    val status: String,
    val items: List<ThreadItem>,
    val startedAt: Long?,
    val completedAt: Long?,
    val durationMs: Long?,
    val error: TurnError?,
    val raw: JsonObject,
)

data class TurnError(
    val message: String,
    val additionalDetails: String?,
    val codexErrorInfo: String?,
)

data class ThreadItem(
    val id: String,
    val type: String,
    val status: String,
    val raw: JsonObject,
) {
    val text: String
        get() = raw.string("text").orEmpty()

    val command: String?
        get() = raw.string("command")

    val aggregatedOutput: String?
        get() = raw.string("aggregatedOutput")

    val exitCode: Int?
        get() = raw.int("exitCode")

    val server: String?
        get() = raw.string("server")

    val tool: String?
        get() = raw.string("tool")
}

data class TurnPage(
    val turns: List<ThreadTurn>,
    val nextCursor: String?,
    val backwardsCursor: String?,
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val hidden: Boolean,
    val defaultReasoningEffort: String?,
    val supportedReasoningEfforts: List<ReasoningEffortOption>,
    val inputModalities: Set<String>,
)

data class ReasoningEffortOption(
    val effort: String,
    val description: String,
)

data class CollaborationMode(
    val name: String,
    val mode: String,
    val model: String?,
    val reasoningEffort: String?,
)

data class PermissionProfile(
    val id: String,
    val description: String,
    val allowed: Boolean,
)

sealed interface RequestId {
    val wireValue: Any

    data class Number(val value: Long) : RequestId {
        override val wireValue: Any get() = value
    }

    data class Text(val value: String) : RequestId {
        override val wireValue: Any get() = value
    }

    companion object {
        fun fromJson(value: JsonPrimitive): RequestId =
            value.longOrNull?.let(::Number) ?: Text(value.content)
    }
}

data class RpcServerError(
    val code: Int?,
    val message: String,
    val data: JsonObject?,
)

class RpcException(
    val code: Int? = null,
    val serverData: JsonObject? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class ApprovalKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
    USER_INPUT,
    MCP_ELICITATION,
    UNKNOWN,
}

data class ApprovalRequest(
    val requestId: RequestId,
    val method: String,
    val kind: ApprovalKind,
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val title: String,
    val detail: String,
    val params: JsonObject,
)

data class InputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<String>,
    val isOther: Boolean,
)

sealed interface CodexEvent {
    data class Initialized(val result: InitializeResult) : CodexEvent
    data class ItemUpsert(val threadId: String?, val item: ThreadItem) : CodexEvent
    data class AgentDelta(val threadId: String?, val itemId: String, val delta: String) : CodexEvent
    data class PlanDelta(val threadId: String?, val itemId: String, val delta: String) : CodexEvent
    data class ReasoningDelta(val threadId: String?, val itemId: String, val delta: String) : CodexEvent
    data class CommandOutputDelta(val threadId: String?, val itemId: String, val delta: String) : CodexEvent
    data class TurnChanged(
        val threadId: String?,
        val turnId: String?,
        val running: Boolean,
        val status: String? = null,
        val error: String? = null,
    ) : CodexEvent

    data class ThreadChanged(val threadId: String?, val kind: String) : CodexEvent
    data class ApprovalRequested(val request: ApprovalRequest) : CodexEvent
    data class Warning(val message: String) : CodexEvent
    data class ServerError(val threadId: String?, val message: String, val code: String? = null) : CodexEvent
    data class Diagnostic(val message: String) : CodexEvent
    data class Disconnected(val cause: Throwable?) : CodexEvent
}

data class ThreadStartOptions(
    val cwd: String,
    val model: String? = null,
    val approvalPolicy: String = "on-request",
    val approvalsReviewer: String = "user",
    val sandbox: String = "workspace-write",
    val permissionProfile: String? = null,
)

data class TurnOptions(
    val cwd: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
    val sandbox: String? = null,
    val permissionProfile: String? = null,
    val collaborationMode: JsonObject? = null,
)
