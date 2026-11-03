package cc.cherr.codexdeck.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val MAX_DIAGNOSTIC_LOG_BYTES = 256 * 1024
internal const val MAX_DIAGNOSTIC_LOG_LINES = 2_000
private const val MAX_DIAGNOSTIC_LINE_CHARS = 4_000
private const val MAX_PENDING_DIAGNOSTIC_LINES = 256
private const val FILE_FLUSH_TIMEOUT_MS = 5_000L

private val diagnosticTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSXXX")

/**
 * An opt-in, bounded diagnostic sink. No work is formatted, queued, or written while disabled.
 */
internal class DiagnosticLogStore(
    private val file: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxBytes: Int = MAX_DIAGNOSTIC_LOG_BYTES,
    private val maxLines: Int = MAX_DIAGNOSTIC_LOG_LINES,
) : Closeable {
    init {
        require(maxBytes > 0)
        require(maxLines > 0)
    }

    private sealed interface Command {
        data class Append(val line: String) : Command
        data class Flush(val complete: CompletableDeferred<Unit>) : Command
    }

    private val enabled = AtomicBoolean(false)
    private val commands = Channel<Command>(capacity = MAX_PENDING_DIAGNOSTIC_LINES)
    private val fileLock = ReentrantLock()
    private var knownLineCount: Int? = null
    private val writerJob = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Append -> {
                    if (enabled.get()) {
                        runCatching { appendToFile(command.line) }
                    }
                }
                is Command.Flush -> command.complete.complete(Unit)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun log(category: String, message: String) {
        if (!enabled.get()) return
        val safeCategory = sanitizeDiagnosticText(category).take(80)
        val safeMessage = sanitizeDiagnosticText(message).take(MAX_DIAGNOSTIC_LINE_CHARS)
        if (safeMessage.isBlank()) return
        commands.trySend(Command.Append(formatLine(safeCategory, safeMessage)))
    }

    /**
     * Emergency path for an uncaught exception. It writes synchronously before the process exits
     * and bypasses the queue, but still obeys the enabled flag and sanitizer.
     */
    fun logFatal(category: String, message: String) {
        if (!enabled.get()) return
        val safeCategory = sanitizeDiagnosticText(category).take(80)
        val safeMessage = sanitizeDiagnosticText(message).take(MAX_DIAGNOSTIC_LINE_CHARS)
        if (safeMessage.isBlank()) return
        runCatching {
            fileLock.withLock {
                appendToFile(formatLine(safeCategory, safeMessage))
            }
        }
    }

    suspend fun read(): String {
        flush()
        return withContext(Dispatchers.IO) {
            fileLock.withLock {
                if (!file.exists()) "" else runCatching { file.readText() }.getOrDefault("")
            }
        }
    }

    suspend fun clear() {
        flush()
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                runCatching { file.delete() }
                knownLineCount = 0
            }
        }
    }

    override fun close() {
        commands.close()
    }

    private suspend fun flush() {
        if (writerJob.isCompleted) return
        val barrier = Command.Flush(CompletableDeferred())
        val sent = runCatching { commands.send(barrier) }.isSuccess
        if (sent) {
            withTimeoutOrNull(FILE_FLUSH_TIMEOUT_MS) { barrier.complete.await() }
        }
    }

    private fun appendToFile(line: String) {
        fileLock.withLock {
            file.parentFile?.mkdirs()
            val currentLines = knownLineCount ?: if (file.exists()) {
                countLogLines(file.readText())
            } else {
                0
            }
            file.appendText(line + "\n")
            val nextLineCount = currentLines + 1
            knownLineCount = nextLineCount
            if (file.length() > maxBytes || nextLineCount > maxLines) {
                compact()
            }
        }
    }

    private fun compact() {
        val bounded = boundDiagnosticLog(
            text = file.readText(),
            maxBytes = (maxBytes * 3 / 4).coerceAtLeast(1),
            maxLines = (maxLines * 3 / 4).coerceAtLeast(1),
        )
        file.writeText(bounded + if (bounded.isEmpty()) "" else "\n")
        knownLineCount = countLogLines(bounded)
    }

    private fun formatLine(category: String, message: String): String =
        "[${OffsetDateTime.now().format(diagnosticTimeFormat)}][$category] $message"
}

/**
 * Keeps recent complete lines within both limits. UTF-8 byte length is measured, not character
 * count, because diagnostics can contain Chinese or other multi-byte text.
 */
internal fun boundDiagnosticLog(text: String, maxBytes: Int, maxLines: Int): String {
    require(maxBytes > 0)
    require(maxLines > 0)
    if (text.isEmpty()) return ""

    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val selected = normalized.split('\n')
        .dropLastWhile(String::isEmpty)
        .takeLast(maxLines)
        .toMutableList()
    var bytes = selected.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
    while (bytes > maxBytes && selected.size > 1) {
        bytes -= selected.removeAt(0).toByteArray(Charsets.UTF_8).size + 1
    }
    if (selected.size == 1 &&
        selected[0].toByteArray(Charsets.UTF_8).size > maxBytes
    ) {
        var value = selected[0]
        while (value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size > maxBytes) {
            value = value.drop(1)
        }
        selected[0] = value
    }
    return selected.joinToString("\n")
}

/**
 * Best-effort redaction for old diagnostics and third-party stderr. Protocol payloads, chat text,
 * terminal output, and request parameters are never sent to this function by the app.
 */
internal fun sanitizeDiagnosticText(raw: String): String {
    if (raw.isEmpty()) return raw
    var value = PRIVATE_KEY_BLOCK.replace(raw, "[REDACTED PRIVATE KEY]")
    value = BEARER_TOKEN.replace(value, "Bearer [REDACTED]")
    value = CREDENTIAL_FIELD.replace(value) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
    }
    value = URI_USER_INFO.replace(value, "$1[REDACTED]@")
    value = LONG_BASE64_SECRET.replace(value, "[REDACTED]")
    return value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun countLogLines(text: String): Int =
    if (text.isBlank()) 0 else text.lineSequence().count { it.isNotEmpty() }

private val PRIVATE_KEY_BLOCK = Regex(
    """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----""",
    RegexOption.IGNORE_CASE,
)
private val BEARER_TOKEN = Regex(
    """\bBearer\s+[A-Za-z0-9._~+/=-]{6,}""",
    RegexOption.IGNORE_CASE,
)
private val CREDENTIAL_FIELD = Regex(
    """\b(password|passphrase|secret|token|access[_-]?token|refresh[_-]?token|client[_-]?secret|api[_-]?key|authorization)\b(\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)""",
    RegexOption.IGNORE_CASE,
)
private val URI_USER_INFO = Regex(
    """\b([A-Za-z][A-Za-z0-9+.-]*://)[^/\s@]+@""",
)
private val LONG_BASE64_SECRET = Regex(
    """(?=[A-Za-z0-9+/=]{64,})(?=[A-Za-z0-9+/=]*[/+=])[A-Za-z0-9+/=]{64,}""",
)
