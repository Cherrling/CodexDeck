package cc.cherr.codexdeck.transport

import cc.cherr.codexdeck.protocol.JsonLineTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One JSON-RPC message per WebSocket text frame. The server never mixes its startup banner or
 * stderr into this stream, so no line-oriented terminal parsing is involved.
 */
class WebSocketJsonLineTransport private constructor(
    private val url: String,
    private val bearerToken: String?,
    connectTimeout: Duration,
) : JsonLineTransport {
    private val closed = AtomicBoolean()
    private val opened = CompletableDeferred<Unit>()
    private val incomingChannel = Channel<String>(capacity = 512)
    private val writeMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    suspend fun open(): WebSocketJsonLineTransport {
        val request = Request.Builder()
            .url(url)
            .apply {
                bearerToken?.takeIf(String::isNotBlank)?.let { token ->
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        socket = client.newWebSocket(request, Listener())
        return try {
            withTimeout(30.seconds) { opened.await() }
            this
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override suspend fun send(line: String) {
        writeMutex.withLock {
            check(!closed.get()) { "WebSocket transport is closed" }
            val active = socket ?: throw IOException("WebSocket is not connected")
            if (!active.send(line)) {
                throw IOException("WebSocket outbound queue rejected the message")
            }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.cancel()
        incomingChannel.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) {
                webSocket.cancel()
                return
            }
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get()) return
            if (text.length > MAX_PROTOCOL_MESSAGE_CHARS) {
                fail(IOException("WebSocket inbound message exceeds the protocol size limit"))
                return
            }
            val queued = incomingChannel.trySend(text)
            if (queued.isFailure) {
                fail(IOException("WebSocket inbound queue overflow"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            fail(IOException("App-server sent an unsupported binary WebSocket frame"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(IOException("WebSocket closed: $code ${reason.take(200)}"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code?.let { "HTTP $it: " }.orEmpty()
            opened.completeExceptionally(IOException("$status${t.message.orEmpty()}", t))
            finish(t)
        }
    }

    private fun fail(failure: Throwable) {
        opened.completeExceptionally(failure)
        finish(failure)
        socket?.cancel()
    }

    private fun finish(cause: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        incomingChannel.close(cause)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val MAX_PROTOCOL_MESSAGE_CHARS = 16 * 1024 * 1024

        suspend fun connect(
            url: String,
            bearerToken: String?,
            connectTimeout: Duration = 15.seconds,
            allowInsecure: Boolean = false,
        ): WebSocketJsonLineTransport {
            val uri = runCatching { URI(url) }
                .getOrElse { throw IllegalArgumentException("WebSocket URL is invalid", it) }
            require(uri.scheme == "ws" || uri.scheme == "wss") {
                "WebSocket URL must use ws:// or wss://"
            }
            require(!uri.host.isNullOrBlank()) { "WebSocket URL must include a host" }
            require(uri.userInfo == null) { "WebSocket URL must not contain credentials" }
            require(uri.fragment == null) { "WebSocket URL must not contain a fragment" }
            require(uri.scheme != "ws" || allowInsecure || isLoopbackHost(uri.host)) {
                "Cleartext ws:// requires an explicit allow-insecure setting"
            }
            return WebSocketJsonLineTransport(url, bearerToken, connectTimeout).open()
        }

        private fun isLoopbackHost(host: String?): Boolean =
            host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
