package cc.cherr.codexdeck.transport

import cc.cherr.codexdeck.protocol.AppServerClient
import cc.cherr.codexdeck.protocol.ClientInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in integration check:
 *
 * CODEXDECK_WS_TEST_URL=ws://127.0.0.1:8765 \
 * CODEXDECK_WS_TEST_TOKEN=token \
 * ./gradlew :app:testDebugUnitTest --tests '*WebSocketAppServerSmokeTest'
 */
class WebSocketAppServerSmokeTest {
    @Test
    fun initializesAndListsThreadsOverOfficialWebSocketTransport(): Unit = runBlocking {
        val url = System.getenv("CODEXDECK_WS_TEST_URL").orEmpty()
        assumeTrue("CODEXDECK_WS_TEST_URL is not configured", url.isNotBlank())
        val transport = WebSocketJsonLineTransport.connect(
            url = url,
            bearerToken = System.getenv("CODEXDECK_WS_TEST_TOKEN"),
        )
        val client = AppServerClient(
            transport = transport,
            clientInfo = ClientInfo(version = "websocket-smoke-test"),
        )
        try {
            val initialized = client.initialize()
            assertNotNull(initialized.platformOs)
            client.listThreadsPage(limit = 1)
            client.listSkills(cwd = "/tmp")
            client.listMcpServers()
            client.listPlugins(cwd = "/tmp")
        } finally {
            client.close()
        }
        Unit
    }
}
