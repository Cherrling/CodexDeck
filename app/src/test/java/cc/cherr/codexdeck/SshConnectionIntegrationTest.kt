package cc.cherr.codexdeck

import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.data.IdentityRecord
import cc.cherr.codexdeck.data.KnownHostRecord
import cc.cherr.codexdeck.data.SecretCipher
import cc.cherr.codexdeck.protocol.AppServerClient
import cc.cherr.codexdeck.protocol.ClientInfo
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.ssh.ConnectionPromptGateway
import cc.cherr.codexdeck.ssh.HostTrust
import cc.cherr.codexdeck.ssh.SshConnectionManager
import cc.cherr.codexdeck.ssh.SshKeys
import cc.cherr.codexdeck.ssh.TrustDecision
import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Opt-in real SSH test. Run through scripts/ssh_test_server.py.
 */
class SshConnectionIntegrationTest {
    @Test
    fun routesThroughJumpHostStartsAppServerAndOpensSharedPty() = runBlocking {
        val rootPath = System.getenv("SSH_TEST_DIR")
        assumeTrue("SSH_TEST_DIR is not configured", !rootPath.isNullOrBlank())
        val root = File(requireNotNull(rootPath))
        val remoteRoot = File(System.getenv("SSH_TEST_REMOTE_DIR") ?: root.absolutePath)
        val port = requireNotNull(System.getenv("SSH_TEST_PORT")).toInt()
        val username = requireNotNull(System.getenv("SSH_TEST_USER"))
        val keyBytes = File(root, "ed25519-encrypted").readBytes()
        SshKeys.configure()
        for (name in listOf(
            "ed25519",
            "ed25519-encrypted",
            "rsa",
            "rsa-encrypted",
            "rsa-pem",
            "rsa-pem-encrypted",
            "rsa-pkcs8",
            "rsa-pkcs8-encrypted",
            "ed25519-pkcs8",
            "ed25519-pkcs8-encrypted",
            "ecdsa",
            "ecdsa-pkcs8",
        )) {
            SSHClient().use { ssh ->
                ssh.addHostKeyVerifier(
                    HostTrust("127.0.0.1", port, { null }, {}) { TrustDecision.ONCE },
                )
                ssh.connect("127.0.0.1", port)
                val passphrase = if (name.endsWith("-encrypted")) "test-passphrase" else ""
                val key = SshKeys.loadWithPassphraseRequest(
                    ssh,
                    File(root, name).readBytes(),
                ) { passphrase.toCharArray() }
                val publicName = when {
                    name.startsWith("rsa-pem") || name.startsWith("rsa-pkcs8") -> "rsa"
                    name.startsWith("ed25519-pkcs8") -> "ed25519-pkcs8"
                    name.startsWith("ecdsa") -> "ecdsa"
                    else -> name
                }
                assertEquals(
                    name,
                    File(root, "$publicName.pub").readText().trim().split(" ").take(2)
                        .joinToString(" "),
                    SshKeys.publicKey(key.getPublic()),
                )
            }
        }
        val identity = IdentityRecord().apply {
            id = "identity"
            label = "Integration key"
            encryptedPrivateKey = keyBytes
        }
        fun host(id: String, hostname: String, jumpHostId: String? = null) =
            HostRecord().apply {
                this.id = id
                label = id
                this.hostname = hostname
                this.port = port
                this.username = username
                authType = "key"
                identityId = identity.id
                this.jumpHostId = jumpHostId
                codexExecutable = File(remoteRoot, "fake-codex").absolutePath
                connectTimeoutMs = 10_000
                keepAliveSeconds = 30
                retryCount = 0
            }

        val gateway = host("gateway", "127.0.0.1")
        val target = host("target", "localhost", jumpHostId = gateway.id)
        val hosts = listOf(gateway, target).associateBy(HostRecord::id)
        val knownHosts = ConcurrentHashMap<String, KnownHostRecord>()
        val manager = SshConnectionManager(
            findHost = hosts::get,
            findIdentity = { id -> identity.takeIf { it.id == id } },
            findKnownHost = { hostname, sshPort -> knownHosts["$hostname:$sshPort"] },
            persistKnownHost = { record ->
                knownHosts["${record.hostname}:${record.port}"] = record
            },
            vault = object : SecretCipher {
                override fun encrypt(context: String, plaintext: ByteArray): ByteArray =
                    plaintext.copyOf()

                override fun decrypt(context: String, blob: ByteArray): ByteArray =
                    blob.copyOf()
            },
        )
        val prompts = ConnectionPromptGateway()
        val promptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val keyPrompts = AtomicInteger()
        val trustPrompts = AtomicInteger()
        val promptJob = promptScope.launch {
            prompts.prompts.collect { prompt ->
                when (prompt) {
                    is ConnectionPrompt.Secret -> {
                        assertEquals(false, prompt.isPassword)
                        keyPrompts.incrementAndGet()
                        prompts.submitSecret(prompt.id, "test-passphrase")
                    }
                    is ConnectionPrompt.HostKey -> {
                        trustPrompts.incrementAndGet()
                        prompts.submitTrust(prompt.id, TrustDecision.SAVE)
                    }
                }
            }
        }
        try {
            val connection = withTimeout(45.seconds) {
                manager.connect(target.id, prompts)
            }
            assertEquals(2, trustPrompts.get())
            assertEquals(1, keyPrompts.get())
            assertEquals(2, knownHosts.size)

            val ready = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val output = StringBuffer()
            val shell = connection.newShellTransport()
            shell.start(
                TerminalSize(80, 24, 8, 16),
                object : TerminalTransport.Listener {
                    override fun onReady() = ready.countDown()

                    override fun onBytes(bytes: ByteArray, length: Int) {
                        output.append(String(bytes, 0, length, Charsets.UTF_8))
                    }

                    override fun onClosed(exitCode: Int) = closed.countDown()
                },
            )
            try {
                assertTrue(
                    "PTY closed before ready",
                    ready.await(20, TimeUnit.SECONDS) || closed.count == 0L,
                )
                val command = "printf 'CODEXDECK_SSH_OK\\n'\r".toByteArray()
                shell.write(command, 0, command.size)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!output.contains("CODEXDECK_SSH_OK") && System.nanoTime() < deadline) {
                    Thread.sleep(20)
                }
                assertTrue("PTY output: $output", output.contains("CODEXDECK_SSH_OK"))
            } finally {
                shell.close()
            }

            val client = AppServerClient(
                transport = connection.openAppServer(target.codexExecutable),
                clientInfo = ClientInfo(version = "ssh-integration-test"),
            )
            try {
                assertEquals(
                    "codexdeck-ssh-integration-test",
                    client.initialize().userAgent,
                )
                assertTrue(client.listThreadsPage(limit = 1).threads.isEmpty())
            } finally {
                client.close()
            }
            assertTrue(manager.isConnected(target.id))
        } finally {
            manager.close()
            prompts.cancelAll()
            promptJob.cancelAndJoin()
        }
    }
}
