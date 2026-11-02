package cc.cherr.codexdeck.ssh

import cc.cherr.codexdeck.protocol.JsonLineTransport
import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One authenticated route can multiplex app-server exec and interactive PTY channels. */
class SharedSshConnection internal constructor(
    val hostId: String,
    private val target: SSHClient,
    private val ownedClients: List<SSHClient>,
) : Closeable {
    private val closed = AtomicBoolean()
    private val channels = java.util.concurrent.ConcurrentLinkedQueue<Closeable>()

    fun openAppServer(executable: String): SshJsonLineTransport {
        checkOpen()
        val session = target.startSession()
        return try {
            val command = session.exec(appServerCommand(executable))
            val transport = SshJsonLineTransport(command, session)
            lateinit var handle: Closeable
            handle = Closeable {
                runBlocking { transport.close() }
                channels.remove(handle)
            }
            channels += handle
            transport.onClosed = {
                channels.remove(handle)
                runCatching { handle.close() }
            }
            transport
        } catch (failure: Throwable) {
            runCatching { session.close() }
            throw failure
        }
    }

    fun newShellTransport(): TerminalTransport {
        checkOpen()
        return SharedSshTerminalTransport(this, target)
    }

    fun openLocalTunnel(remoteHost: String, remotePort: Int): SshLocalTunnel {
        checkOpen()
        require(remotePort in 1..65535) { "Remote tunnel port is invalid" }
        val server = ServerSocket(
            0,
            32,
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
        )
        val forwarder = target.newLocalPortForwarder(
            Parameters("127.0.0.1", server.localPort, remoteHost, remotePort),
            server,
        )
        val tunnelClosed = AtomicBoolean()
        lateinit var handle: Closeable
        handle = Closeable {
            if (tunnelClosed.compareAndSet(false, true)) {
                runCatching { forwarder.close() }
                runCatching { server.close() }
                untrack(handle)
            }
        }
        track(handle)
        Thread({
            runCatching { forwarder.listen() }
                .onFailure { runCatching { handle.close() } }
        }, "codexdeck-ssh-tunnel").apply {
            isDaemon = true
            start()
        }
        return SshLocalTunnel(server.localPort, handle)
    }

    internal fun track(channel: Closeable) {
        check(!closed.get()) { "SSH connection is closed" }
        channels += channel
    }

    internal fun untrack(channel: Closeable) {
        channels.remove(channel)
    }

    internal fun checkOpen() {
        check(!closed.get() && target.isConnected && target.isAuthenticated) {
            "SSH connection is not available"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        while (true) {
            val channel = channels.poll() ?: break
            runCatching { channel.close() }
        }
        ownedClients.asReversed().forEach { client ->
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
    }
}

class SshLocalTunnel internal constructor(
    val localPort: Int,
    private val closeable: Closeable,
) : Closeable {
    override fun close() = closeable.close()
}

class SshJsonLineTransport internal constructor(
    private val command: Session.Command,
    private val session: Session,
) : JsonLineTransport {
    private val closed = AtomicBoolean()
    private val writeMutex = Mutex()
    private val input = command.inputStream.bufferedReader(Charsets.UTF_8)
    private val output = command.outputStream.bufferedWriter(Charsets.UTF_8)
    private val errors = command.errorStream.bufferedReader(Charsets.UTF_8)
    private val lineChannel = Channel<String>(capacity = 256)
    private val diagnosticChannel = Channel<String>(capacity = 128)
    private val stdoutThread = Thread(::readStdout, "codexdeck-appserver-stdout").apply { isDaemon = true }
    private val stderrThread = Thread(::readStderr, "codexdeck-appserver-stderr").apply { isDaemon = true }

    var onClosed: (() -> Unit)? = null

    override val incoming: Flow<String> = lineChannel.receiveAsFlow()
    val diagnostics: Flow<String> = diagnosticChannel.receiveAsFlow()

    init {
        stdoutThread.start()
        stderrThread.start()
    }

    override suspend fun send(line: String) {
        writeMutex.withLock {
            check(!closed.get()) { "App-server channel is closed" }
            output.write(line)
            output.newLine()
            output.flush()
        }
    }

    private fun readStdout() {
        try {
            while (!closed.get()) {
                val line = input.readLine() ?: break
                if (line.length > MAX_PROTOCOL_LINE_CHARS) {
                    throw IOException("app-server stdout line exceeds the protocol size limit")
                }
                if (line.isNotBlank() && lineChannel.trySend(line).isFailure) {
                    throw IOException("app-server stdout queue overflow")
                }
            }
        } catch (error: Throwable) {
            diagnosticChannel.trySend("app-server stdout: ${error.message.orEmpty()}")
        } finally {
            closeBlocking()
        }
    }

    private fun readStderr() {
        try {
            errors.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) diagnosticChannel.trySend(line)
                }
            }
        } catch (_: Throwable) {
            // The channel close path reports the actual disconnect.
        } finally {
            diagnosticChannel.close()
        }
    }

    private fun closeBlocking() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { command.close() }
        runCatching { session.close() }
        lineChannel.close()
        diagnosticChannel.close()
        onClosed?.invoke()
    }

    override suspend fun close() = closeBlocking()

    private companion object {
        const val MAX_PROTOCOL_LINE_CHARS = 16 * 1024 * 1024
    }
}

/**
 * Termux's terminal emulator consumes this transport. Closing a terminal channel does not close
 * the shared SSH route; closing the route closes every channel.
 */
private class SharedSshTerminalTransport(
    private val connection: SharedSshConnection,
    private val ssh: SSHClient,
) : TerminalTransport {
    private val closed = AtomicBoolean()
    private val outputLock = Any()
    private val pendingOutput = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var writing = false
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "codexdeck-shell-reader") }
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256),
        { Thread(it, "codexdeck-shell-writer") },
    )
    private var shell: Session.Shell? = null
    private var session: Session? = null
    private var listener: TerminalTransport.Listener? = null
    private val channelHandle = Closeable { close() }

    init {
        connection.track(channelHandle)
    }

    override fun start(initial: TerminalSize, listener: TerminalTransport.Listener) {
        this.listener = listener
        reader.execute {
            var status = 1
            try {
                connection.checkOpen()
                val channel = ssh.startSession().also { session = it }
                channel.allocatePTY(
                    "xterm-256color",
                    initial.columns,
                    initial.rows,
                    initial.windowWidthPixels(),
                    initial.windowHeightPixels(),
                    emptyMap(),
                )
                runCatching { channel.setEnvVar("COLORTERM", "truecolor") }
                val active = channel.startShell().also { shell = it }
                check(!closed.get())
                listener.onReady()
                val bytes = ByteArray(16 * 1024)
                while (!closed.get()) {
                    val count = active.inputStream.read(bytes)
                    if (count < 0) break
                    if (count > 0) listener.onBytes(bytes, count)
                }
                status = 0
            } finally {
                listener.onClosed(status)
                close()
            }
        }
    }

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        synchronized(outputLock) {
            if (closed.get()) return
            if (count > 1024 * 1024 - queuedBytes) {
                close()
                return
            }
            pendingOutput.addLast(bytes.copyOfRange(offset, offset + count))
            queuedBytes += count
            if (!writing) {
                writing = true
                enqueueWrite()
            }
        }
    }

    private fun enqueueWrite() {
        try {
            writer.execute { drainWrites() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            close()
        }
    }

    private fun drainWrites() {
        while (!closed.get()) {
            val bytes = synchronized(outputLock) {
                if (pendingOutput.isEmpty()) {
                    writing = false
                    return
                }
                pendingOutput.removeFirst()
            }
            try {
                shell?.outputStream?.apply {
                    write(bytes)
                    flush()
                }
            } catch (_: Throwable) {
                close()
            } finally {
                synchronized(outputLock) { queuedBytes -= bytes.size }
                bytes.fill(0)
            }
        }
    }

    override fun resize(size: TerminalSize, onApplied: Runnable) {
        try {
            writer.execute {
                if (closed.get()) return@execute
                runCatching {
                    shell?.changeWindowDimensions(
                        size.columns,
                        size.rows,
                        size.windowWidthPixels(),
                        size.windowHeightPixels(),
                    )
                    onApplied.run()
                }.onFailure { close() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { shell?.close() }
        runCatching { session?.close() }
        synchronized(outputLock) {
            pendingOutput.forEach { it.fill(0) }
            pendingOutput.clear()
        }
        writer.shutdownNow()
        reader.shutdownNow()
        connection.untrack(channelHandle)
    }
}
