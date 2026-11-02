package cc.cherr.codexdeck.ssh

import cc.cherr.codexdeck.data.HostRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class HostPin(
    val algorithm: String,
    val fingerprint: String,
)

data class HostChallenge(
    val hostname: String,
    val port: Int,
    val presented: HostPin,
    val previous: HostPin?,
)

enum class TrustDecision {
    CANCEL,
    ONCE,
    SAVE,
}

sealed interface ConnectionPrompt {
    val id: String

    data class Secret(
        override val id: String = UUID.randomUUID().toString(),
        val hostLabel: String,
        val detail: String,
        val isPassword: Boolean,
    ) : ConnectionPrompt

    data class HostKey(
        override val id: String = UUID.randomUUID().toString(),
        val challenge: HostChallenge,
    ) : ConnectionPrompt
}

/**
 * Bridges SSHJ's blocking verifier/authentication callbacks to Android UI without leaking
 * credentials into a repository or state snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPromptGateway {
    private val promptChannel = Channel<ConnectionPrompt>(Channel.UNLIMITED)
    private val secretRequests = ConcurrentHashMap<String, CompletableDeferred<CharArray?>>()
    private val trustRequests = ConcurrentHashMap<String, CompletableDeferred<TrustDecision>>()

    val prompts: Flow<ConnectionPrompt> = promptChannel.receiveAsFlow()

    fun requestSecret(prompt: ConnectionPrompt.Secret): CharArray? {
        val deferred = CompletableDeferred<CharArray?>()
        secretRequests[prompt.id] = deferred
        promptChannel.trySend(prompt)
        return try {
            runBlocking { withTimeout(120_000) { deferred.await() } }
        } catch (_: Exception) {
            null
        } finally {
            secretRequests.remove(prompt.id)
        }
    }

    fun requestTrust(challenge: HostChallenge): TrustDecision {
        val prompt = ConnectionPrompt.HostKey(challenge = challenge)
        val deferred = CompletableDeferred<TrustDecision>()
        trustRequests[prompt.id] = deferred
        promptChannel.trySend(prompt)
        return try {
            runBlocking { withTimeout(120_000) { deferred.await() } }
        } catch (_: Exception) {
            TrustDecision.CANCEL
        } finally {
            trustRequests.remove(prompt.id)
        }
    }

    fun submitSecret(id: String, secret: String) {
        val chars = secret.toCharArray()
        val accepted = if (chars.isEmpty()) {
            chars.fill('\u0000')
            secretRequests[id]?.complete(null) == true
        } else {
            secretRequests[id]?.complete(chars) == true
        }
        if (!accepted) chars.fill('\u0000')
    }

    fun submitTrust(id: String, decision: TrustDecision) {
        trustRequests[id]?.complete(decision)
    }

    fun cancelAll() {
        secretRequests.values.forEach { deferred ->
            if (deferred.isCompleted) {
                runCatching { deferred.getCompleted() }.getOrNull()?.fill('\u0000')
            }
            deferred.complete(null)
        }
        secretRequests.clear()
        trustRequests.values.forEach { it.complete(TrustDecision.CANCEL) }
        trustRequests.clear()
    }
}

fun jumpRoute(target: HostRecord, find: (String) -> HostRecord?): List<HostRecord> {
    val route = mutableListOf<HostRecord>()
    val seen = mutableSetOf<String>()
    var current = target
    while (true) {
        require(seen.add(current.id)) { "跳板机配置形成循环" }
        require(route.size < 5) { "最多支持四层跳板机" }
        route += current
        val nextId = current.jumpHostId ?: break
        current = requireNotNull(find(nextId)) { "跳板机不存在，请重新配置" }
    }
    return route.asReversed()
}

fun HostRecord.usesSshTransport(): Boolean =
    appServerTransport == "ssh" ||
        (appServerTransport == "websocket" && webSocketViaSsh)

fun validateHost(host: HostRecord): String? {
    if (host.appServerTransport == "websocket") {
        validateWebSocketUrl(
            value = host.appServerUrl,
            allowInsecure = host.allowInsecureWebSocket,
            viaSshTunnel = host.webSocketViaSsh,
        )?.let { return it }
    }
    when {
        host.label.isBlank() -> "请填写服务器名称"
        host.appServerTransport !in setOf("ssh", "websocket") -> "不支持的 Codex 连接方式"
        host.connectTimeoutMs !in 3_000..120_000 -> "连接超时必须为 3–120 秒"
        host.keepAliveSeconds != 0 && host.keepAliveSeconds !in 30..600 ->
            "Keepalive 必须为 0 或 30–600 秒"
        host.retryCount !in 0..3 -> "重试次数必须为 0–3"
        else -> null
    }?.let { return it }
    if (!host.usesSshTransport()) return null
    return when {
        host.hostname.isBlank() || host.hostname.any { it.isWhitespace() || it == '/' } ->
            "请填写有效的主机名或 IP"
        host.port !in 1..65535 -> "SSH 端口必须在 1–65535"
        host.username.isBlank() -> "请填写用户名"
        host.authType !in setOf("key", "password") -> "不支持的认证方式"
        host.appServerTransport == "ssh" && (
            host.codexExecutable.isBlank() || host.codexExecutable.length > 1024 ||
                host.codexExecutable.any(Char::isISOControl)
            ) -> "Codex 可执行文件路径无效"
        host.authType == "key" && host.identityId.isNullOrBlank() -> "请选择 SSH 身份"
        else -> null
    }
}

private fun validateWebSocketUrl(
    value: String,
    allowInsecure: Boolean,
    viaSshTunnel: Boolean,
): String? {
    if (value.isBlank() || value.length > 2048) return "请填写有效的 WebSocket URL"
    val uri = runCatching { URI(value) }.getOrNull() ?: return "WebSocket URL 格式无效"
    return when {
        uri.scheme !in setOf("ws", "wss") -> "WebSocket URL 必须使用 ws:// 或 wss://"
        uri.host.isNullOrBlank() -> "WebSocket URL 缺少主机名"
        uri.userInfo != null -> "WebSocket URL 不能包含用户名或密码"
        uri.fragment != null -> "WebSocket URL 不能包含 fragment"
        viaSshTunnel && uri.scheme != "ws" -> "SSH Tunnel 仅支持端口转发 ws:// 地址"
        !viaSshTunnel && uri.scheme == "ws" && !allowInsecure && !isLoopbackHost(uri.host) ->
            "非回环 ws:// 必须显式允许明文连接；建议使用 wss://"
        else -> null
    }
}

private fun isLoopbackHost(host: String): Boolean =
    host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

internal fun appServerCommand(executable: String): String {
    require(executable.isNotBlank() && executable.none(Char::isISOControl))
    return "exec ${shellQuote(executable)} app-server --listen stdio://"
}
