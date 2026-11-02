package cc.cherr.codexdeck.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebSocketJsonLineTransportTest {
    @Test
    fun exchangesTextFramesAndSendsBearerToken() = runBlocking {
        val receivedByServer = CompletableDeferred<String>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("{\"hello\":\"client\"}")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedByServer.complete(text)
                    }
                },
            ),
        )
        server.start()
        try {
            val transport = WebSocketJsonLineTransport.connect(
                url = "ws://${server.hostName}:${server.port}/rpc",
                bearerToken = "capability-token",
            )
            assertEquals(
                "Bearer capability-token",
                server.takeRequest().getHeader("Authorization"),
            )
            assertEquals(
                "{\"hello\":\"client\"}",
                withTimeout(5_000) { transport.incoming.first() },
            )
            transport.send("{\"hello\":\"server\"}")
            assertEquals(
                "{\"hello\":\"server\"}",
                withTimeout(5_000) { receivedByServer.await() },
            )
            transport.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsCredentialsEmbeddedInUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                WebSocketJsonLineTransport.connect(
                    url = "wss://user:secret@example.com/rpc",
                    bearerToken = null,
                )
            }
        }
    }

    @Test
    fun rejectsRemoteCleartextUnlessExplicitlyAllowed() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                WebSocketJsonLineTransport.connect(
                    url = "ws://192.0.2.10:8765/rpc",
                    bearerToken = null,
                )
            }
        }
    }
}
