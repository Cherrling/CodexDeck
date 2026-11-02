package cc.cherr.codexdeck.ssh

import cc.cherr.codexdeck.data.CredentialVault
import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.data.KnownHostRecord
import cc.cherr.codexdeck.data.StoreDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

class SshConnectionManager(
    private val dao: StoreDao,
    private val vault: CredentialVault,
) : AutoCloseable {
    private val connections = ConcurrentHashMap<String, SharedSshConnection>()
    private val closed = AtomicBoolean()

    suspend fun connect(
        hostId: String,
        gateway: ConnectionPromptGateway,
    ): SharedSshConnection = withContext(Dispatchers.IO) {
        check(!closed.get()) { "SSH manager is closed" }
        connections[hostId]?.takeIf { connection ->
            runCatching { connection.checkOpen() }.isSuccess
        }?.let { return@withContext it }
        connections.remove(hostId)?.close()

        val target = requireNotNull(dao.host(hostId)) { "服务器不存在" }
        val route = jumpRoute(target, dao::host)
        route.forEach { host ->
            validateHost(host)?.let { throw SshConfigurationException(it) }
        }
        val secrets = RouteSecrets(dao, vault, gateway)
        var attempt = 0
        var lastFailure: Throwable? = null
        try {
            while (attempt <= target.retryCount) {
                try {
                    val connection = establish(target, route, gateway, secrets)
                    connections[hostId]?.close()
                    connections[hostId] = connection
                    return@withContext connection
                } catch (failure: Throwable) {
                    secrets.wipeEphemeral()
                    lastFailure = failure
                    if (!retryable(failure) || attempt >= target.retryCount) throw failure
                    attempt++
                    delay(400L * attempt)
                }
            }
            throw lastFailure ?: IllegalStateException("SSH connection failed")
        } finally {
            secrets.wipeAll()
        }
    }

    fun close(hostId: String) {
        connections.remove(hostId)?.close()
    }

    fun isConnected(hostId: String): Boolean =
        connections[hostId]?.let { connection -> runCatching { connection.checkOpen() }.isSuccess } == true

    private fun establish(
        target: HostRecord,
        route: List<HostRecord>,
        gateway: ConnectionPromptGateway,
        secrets: RouteSecrets,
    ): SharedSshConnection {
        SshKeys.configure()
        val ownedClients = mutableListOf<SSHClient>()
        val firstSocket = Socket()
        var previous: SSHClient? = null
        try {
            route.forEach { endpoint ->
                val config = androidCompatibleSshConfig().apply {
                    keepAliveProvider = net.schmizz.keepalive.KeepAliveProvider.KEEP_ALIVE
                }
                val client = SSHClient(config)
                ownedClients += client
                client.connectTimeout = endpoint.connectTimeoutMs
                client.timeout = endpoint.connectTimeoutMs
                client.transport.timeoutMs = endpoint.connectTimeoutMs
                client.connection.keepAlive.keepAliveInterval = endpoint.keepAliveSeconds
                (client.connection.keepAlive as net.schmizz.keepalive.KeepAliveRunner).maxAliveCount = 3
                client.addHostKeyVerifier(
                    HostTrust(
                        requestedHostname = endpoint.hostname,
                        requestedPort = endpoint.port,
                        read = {
                            dao.knownHost(endpoint.hostname, endpoint.port)?.let {
                                HostPin(it.algorithm, it.fingerprint)
                            }
                        },
                        save = { pin ->
                            val now = System.currentTimeMillis()
                            val existing = dao.knownHost(endpoint.hostname, endpoint.port)
                            dao.saveKnownHost(KnownHostRecord().apply {
                                hostname = endpoint.hostname
                                port = endpoint.port
                                algorithm = pin.algorithm
                                fingerprint = pin.fingerprint
                                firstSeenAt = existing?.firstSeenAt ?: now
                                lastVerifiedAt = now
                            })
                        },
                        ask = gateway::requestTrust,
                    ),
                )
                if (previous == null) {
                    client.socketFactory = object : SocketFactory() {
                        override fun createSocket(): Socket = firstSocket
                        override fun createSocket(host: String?, port: Int): Socket = error("Unused")
                        override fun createSocket(
                            host: String?,
                            port: Int,
                            localHost: InetAddress?,
                            localPort: Int,
                        ): Socket = error("Unused")
                        override fun createSocket(host: InetAddress?, port: Int): Socket = error("Unused")
                        override fun createSocket(
                            address: InetAddress?,
                            port: Int,
                            localAddress: InetAddress?,
                            localPort: Int,
                        ): Socket = error("Unused")
                    }
                    client.connect(endpoint.hostname, endpoint.port)
                } else {
                    client.connectVia(previous.newDirectConnection(endpoint.hostname, endpoint.port))
                }
                authenticate(client, endpoint, gateway, secrets)
                previous = client
            }
            return SharedSshConnection(
                hostId = target.id,
                target = requireNotNull(previous),
                ownedClients = ownedClients.toList(),
            )
        } catch (failure: Throwable) {
            runCatching { firstSocket.close() }
            ownedClients.asReversed().forEach { client ->
                runCatching { client.disconnect() }
                runCatching { client.close() }
            }
            throw failure
        }
    }

    private fun authenticate(
        client: SSHClient,
        endpoint: HostRecord,
        gateway: ConnectionPromptGateway,
        secrets: RouteSecrets,
    ) {
        if (endpoint.authType == "password") {
            val password = secrets.password(endpoint, gateway)
            try {
                client.authPassword(endpoint.username, password)
            } finally {
                password.fill('\u0000')
            }
            return
        }

        val identity = requireNotNull(dao.identity(endpoint.identityId)) { "SSH 身份不存在" }
        val pem = vault.decrypt("identity:${identity.id}", identity.encryptedPrivateKey)
        try {
            val key = SshKeys.loadWithPassphraseRequest(client, pem) {
                secrets.passphrase(identity.id, identity.label, endpoint.label, gateway)
            }
            client.authPublickey(endpoint.username, key)
        } finally {
            pem.fill(0)
        }
    }

    private fun retryable(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is SecurityException,
                is net.schmizz.sshj.userauth.UserAuthException,
                is SshConfigurationException,
                -> return false
                is UnknownHostException,
                is SocketTimeoutException,
                is SocketException,
                is java.io.IOException,
                -> return true
            }
            current = current.cause
        }
        return false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connections.values.forEach { it.close() }
        connections.clear()
    }
}

private class RouteSecrets(
    private val dao: StoreDao,
    private val vault: CredentialVault,
    private val gateway: ConnectionPromptGateway,
) {
    private val passwords = mutableMapOf<String, CharArray>()
    private val passphrases = mutableMapOf<String, CharArray>()

    fun password(host: HostRecord, gateway: ConnectionPromptGateway): CharArray {
        passwords[host.id]?.let { return it.copyOf() }
        val stored = host.encryptedPassword
        val value = if (stored.isNotEmpty()) {
            val bytes = vault.decrypt("host-password:${host.id}", stored)
            try {
                val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
                CharArray(decoded.remaining()).also { decoded.get(it) }
            } finally {
                bytes.fill(0)
            }
        } else {
            gateway.requestSecret(
                ConnectionPrompt.Secret(
                    hostLabel = host.label,
                    detail = "输入 ${host.username}@${host.hostname} 的登录密码",
                    isPassword = true,
                ),
            ) ?: throw SshAuthenticationCancelled("已取消密码输入")
        }
        passwords[host.id] = value
        return value.copyOf()
    }

    fun passphrase(
        identityId: String,
        identityLabel: String,
        hostLabel: String,
        gateway: ConnectionPromptGateway,
    ): CharArray {
        passphrases[identityId]?.let { return it.copyOf() }
        val value = gateway.requestSecret(
            ConnectionPrompt.Secret(
                hostLabel = hostLabel,
                detail = "解锁 SSH 身份「$identityLabel」的私钥",
                isPassword = false,
            ),
        ) ?: throw SshAuthenticationCancelled("已取消私钥口令输入")
        passphrases[identityId] = value
        return value.copyOf()
    }

    fun wipeEphemeral() {
        passwords.values.forEach { it.fill('\u0000') }
        passwords.clear()
        passphrases.values.forEach { it.fill('\u0000') }
        passphrases.clear()
    }

    fun wipeAll() = wipeEphemeral()
}

class SshConfigurationException(message: String) : IllegalArgumentException(message)
class SshAuthenticationCancelled(message: String) : SecurityException(message)

internal fun androidCompatibleSshConfig() = DefaultSecurityProviderConfig().apply {
    keyExchangeFactories = keyExchangeFactories.filterNot {
        it.name.contains("curve25519", ignoreCase = true)
    }
}
