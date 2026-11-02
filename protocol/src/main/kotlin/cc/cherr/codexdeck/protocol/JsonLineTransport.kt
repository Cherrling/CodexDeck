package cc.cherr.codexdeck.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Newline-delimited JSON transport. Implementations own their socket or process stream and
 * must deliver each UTF-8 JSON line intact. Protocol framing never leaks into this interface.
 */
interface JsonLineTransport {
    val incoming: Flow<String>
    suspend fun send(line: String)
    suspend fun close()
}
