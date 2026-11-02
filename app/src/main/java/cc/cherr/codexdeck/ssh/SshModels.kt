package cc.cherr.codexdeck.ssh

import cc.cherr.codexdeck.data.HostRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

fun validateHost(host: HostRecord): String? = when {
    host.label.isBlank() -> "请填写服务器名称"
    host.hostname.isBlank() || host.hostname.any { it.isWhitespace() || it == '/' } -> "请填写有效的主机名或 IP"
    host.port !in 1..65535 -> "SSH 端口必须在 1–65535"
    host.username.isBlank() -> "请填写用户名"
    host.authType !in setOf("key", "password") -> "不支持的认证方式"
    host.connectTimeoutMs !in 3_000..120_000 -> "连接超时必须为 3–120 秒"
    host.keepAliveSeconds != 0 && host.keepAliveSeconds !in 30..600 -> "Keepalive 必须为 0 或 30–600 秒"
    host.retryCount !in 0..3 -> "重试次数必须为 0–3"
    host.codexExecutable.isBlank() || host.codexExecutable.length > 1024 ||
        host.codexExecutable.any(Char::isISOControl) -> "Codex 可执行文件路径无效"
    host.authType == "key" && host.identityId.isNullOrBlank() -> "请选择 SSH 身份"
    else -> null
}

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

internal fun appServerCommand(executable: String): String {
    require(executable.isNotBlank() && executable.none(Char::isISOControl))
    return "exec ${shellQuote(executable)} app-server --listen stdio://"
}
