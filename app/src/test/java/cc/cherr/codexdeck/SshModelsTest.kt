package cc.cherr.codexdeck

import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.ssh.appServerCommand
import cc.cherr.codexdeck.ssh.appServerProxyCommand
import cc.cherr.codexdeck.ssh.jumpRoute
import cc.cherr.codexdeck.ssh.shellQuote
import cc.cherr.codexdeck.ssh.validateHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshModelsTest {
    @Test
    fun jumpRouteResolvesOuterGatewayFirstAndSupportsFourHops() {
        val gatewayA = host("a")
        val gatewayB = host("b", jumpHostId = "a")
        val target = host("target", jumpHostId = "b")
        val records = listOf(gatewayA, gatewayB, target).associateBy(HostRecord::id)

        assertEquals(
            listOf("a", "b", "target"),
            jumpRoute(target, records::get).map(HostRecord::id),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun jumpRouteRejectsCycle() {
        val first = host("first", jumpHostId = "second")
        val second = host("second", jumpHostId = "first")
        jumpRoute(first, listOf(first, second).associateBy(HostRecord::id)::get)
    }

    @Test
    fun appServerCommandQuotesExecutableWithoutAdditionalShellSyntax() {
        assertEquals(
            "exec 'codex' app-server --listen stdio://",
            appServerCommand("codex"),
        )
        assertEquals(
            "'a'\"'\"'b'",
            shellQuote("a'b"),
        )
        assertTrue(appServerCommand("/opt/codex/bin/codex").contains("exec '/opt/codex/bin/codex'"))
        assertEquals(
            "exec 'codex' app-server proxy",
            appServerProxyCommand("codex"),
        )
        assertEquals(
            "exec 'a'\"'\"'b' app-server proxy",
            appServerProxyCommand("a'b"),
        )
    }

    @Test
    fun existingAppServerModeRequiresSshExecutable() {
        val host = host("existing").apply {
            appServerTransport = "ssh-proxy"
        }

        assertNull(validateHost(host))

        host.codexExecutable = ""
        assertTrue(validateHost(host)?.contains("Codex 可执行文件") == true)
    }

    @Test
    fun hostValidationChecksSecurityRelevantRanges() {
        val valid = host("valid").apply {
            label = "Dev"
            hostname = "dev.example.com"
            username = "dev"
            port = 22
            identityId = "identity"
        }
        assertNull(validateHost(valid))

        val invalid = HostRecord().apply {
            id = "invalid"
            authType = "key"
            connectTimeoutMs = 1
            keepAliveSeconds = 10
            retryCount = 8
        }
        assertTrue(validateHost(invalid) != null)
    }

    @Test
    fun websocketValidationRequiresTlsOrExplicitCleartextOptIn() {
        val host = HostRecord().apply {
            id = "ws"
            label = "WebSocket"
            appServerTransport = "websocket"
            appServerUrl = "ws://192.0.2.10:8765/rpc"
        }
        assertTrue(validateHost(host)?.contains("ws://") == true)

        host.allowInsecureWebSocket = true
        assertNull(validateHost(host))

        host.allowInsecureWebSocket = false
        host.appServerUrl = "wss://codex.example.com/rpc"
        assertNull(validateHost(host))
    }

    @Test
    fun directWebSocketDoesNotRequireUnusedSshFields() {
        val host = HostRecord().apply {
            id = "direct"
            label = "Direct"
            appServerTransport = "websocket"
            appServerUrl = "wss://codex.example.com/rpc"
            hostname = ""
            username = ""
            authType = ""
            identityId = null
            codexExecutable = ""
            jumpHostId = "saved-for-ssh-mode"
        }

        assertNull(validateHost(host))
    }

    @Test
    fun sshTunnelAllowsRemoteLoopbackWebSocketWithoutGlobalCleartextOptIn() {
        val host = host("ws").apply {
            appServerTransport = "websocket"
            appServerUrl = "ws://127.0.0.1:8765/rpc"
            webSocketViaSsh = true
        }

        assertNull(validateHost(host))

        host.username = ""
        assertTrue(validateHost(host)?.contains("用户名") == true)
    }

    private fun host(id: String, jumpHostId: String? = null) = HostRecord().apply {
        this.id = id
        label = id
        hostname = "$id.example"
        username = "dev"
        identityId = "identity"
        this.jumpHostId = jumpHostId
    }
}
