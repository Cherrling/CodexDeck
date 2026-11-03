package cc.cherr.codexdeck.ssh

import cc.cherr.codexdeck.transport.WebSocketJsonLineTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exposes the raw `codex app-server proxy` duplex stream as one loopback TCP connection so
 * OkHttp can perform the WebSocket upgrade required by the managed daemon control socket.
 */
internal class SshWebSocketBridge(
    private val connection: SharedSshConnection,
    private val command: Session.Command,
    private val session: Session,
    connectTimeout: Duration,
) : Closeable {
    private val closed = AtomicBoolean()
    private val diagnosticChannel = Channel<String>(capacity = 128)
    private val lastDiagnostic = AtomicReference<String?>()
    private val activeSocket = AtomicReference<Socket?>()
    private val server = ServerSocket(
        0,
        1,
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
    ).apply {
        soTimeout = connectTimeout.inWholeMilliseconds.toInt().coerceAtLeast(1_000)
    }
    private val bridgeToken = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private val handle = Closeable { close() }
    private val acceptThread = Thread(::acceptAndPump, "codexdeck-appserver-websocket-bridge")
        .apply { isDaemon = true }
    private val stderrThread = Thread(::readStderr, "codexdeck-appserver-websocket-stderr")
        .apply { isDaemon = true }

    val webSocketUrl: String = "ws://127.0.0.1:${server.localPort}/rpc"
    val webSocketHeaders: Map<String, String> = mapOf(BRIDGE_HEADER to bridgeToken)
    val diagnostics: Flow<String> = diagnosticChannel.receiveAsFlow()
    val lastDiagnosticMessage: String?
        get() = lastDiagnostic.get()

    suspend fun awaitDiagnostic(
        timeout: Duration = 750.milliseconds,
    ): String? = lastDiagnostic.get() ?: withTimeoutOrNull(timeout) {
        runCatching { diagnosticChannel.receive() }.getOrNull()
    }

    init {
        try {
            connection.track(handle)
            acceptThread.start()
            stderrThread.start()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun acceptAndPump() {
        try {
            val socket = server.accept().also(activeSocket::set)
            val clientInput = BufferedInputStream(socket.getInputStream())
            val handshake = readClientHandshake(clientInput)
            if (!hasBridgeToken(handshake)) {
                throw IOException("local WebSocket bridge authentication failed")
            }
            command.outputStream.write(handshake)
            command.outputStream.flush()
            val toCommand = Thread(
                {
                    pump(
                        source = clientInput,
                        sink = command.outputStream,
                        label = "client-to-proxy",
                    )
                },
                "codexdeck-appserver-websocket-upstream",
            ).apply { isDaemon = true }
            val fromCommand = Thread(
                {
                    pump(
                        source = command.inputStream,
                        sink = socket.getOutputStream(),
                        label = "proxy-to-client",
                    )
                },
                "codexdeck-appserver-websocket-downstream",
            ).apply { isDaemon = true }
            toCommand.start()
            fromCommand.start()
            toCommand.join()
            fromCommand.join()
        } catch (_: SocketTimeoutException) {
            reportDiagnostic("WebSocket bridge timed out waiting for the local client")
        } catch (failure: Throwable) {
            if (!closed.get()) {
                reportDiagnostic(
                    "WebSocket bridge failed: ${failure.message ?: failure.javaClass.simpleName}",
                )
            }
        } finally {
            close()
        }
    }

    private fun readClientHandshake(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_HANDSHAKE_BYTES) {
            val next = input.read()
            if (next < 0) throw IOException("local client closed during WebSocket upgrade")
            output.write(next)
            val bytes = output.toByteArray()
            if (bytes.size >= 4 &&
                bytes[bytes.lastIndex - 3] == '\r'.code.toByte() &&
                bytes[bytes.lastIndex - 2] == '\n'.code.toByte() &&
                bytes[bytes.lastIndex - 1] == '\r'.code.toByte() &&
                bytes[bytes.lastIndex] == '\n'.code.toByte()
            ) {
                return bytes
            }
        }
        throw IOException("local WebSocket upgrade headers exceed the size limit")
    }

    private fun hasBridgeToken(handshake: ByteArray): Boolean {
        val text = handshake.toString(Charsets.ISO_8859_1)
        return text.lineSequence().drop(1).any { line ->
            val separator = line.indexOf(':')
            separator > 0 &&
                line.substring(0, separator).trim().equals(BRIDGE_HEADER, ignoreCase = true) &&
                line.substring(separator + 1).trim() == bridgeToken
        }
    }

    private fun pump(source: InputStream, sink: OutputStream, label: String) {
        try {
            val buffer = ByteArray(16 * 1024)
            while (!closed.get()) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                sink.write(buffer, 0, count)
                sink.flush()
            }
        } catch (failure: Throwable) {
            if (!closed.get()) {
                reportDiagnostic(
                    "WebSocket bridge $label failed: " +
                        (failure.message ?: failure.javaClass.simpleName),
                )
            }
        } finally {
            close()
        }
    }

    private fun readStderr() {
        try {
            command.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) reportDiagnostic(line)
                }
            }
        } catch (_: Throwable) {
            // The bridge close path reports transport failure.
        }
    }

    private fun reportDiagnostic(message: String) {
        lastDiagnostic.set(message)
        diagnosticChannel.trySend(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { activeSocket.getAndSet(null)?.close() }
        runCatching { command.close() }
        runCatching { session.close() }
        diagnosticChannel.close()
        connection.untrack(handle)
    }

    private companion object {
        const val BRIDGE_HEADER = "X-CodexDeck-Bridge"
        const val MAX_HANDSHAKE_BYTES = 32 * 1024
    }
}

internal class SshWebSocketJsonLineTransport(
    private val webSocket: WebSocketJsonLineTransport,
    private val bridge: SshWebSocketBridge,
) : cc.cherr.codexdeck.protocol.JsonLineTransport, DiagnosticTransport {
    override val incoming: Flow<String> = webSocket.incoming
    override val diagnostics: Flow<String> = bridge.diagnostics

    override suspend fun send(line: String) = webSocket.send(line)

    override suspend fun close() {
        webSocket.close()
        bridge.close()
    }
}
