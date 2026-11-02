package cc.cherr.codexdeck.data

import cc.cherr.codexdeck.protocol.CodexThread
import cc.cherr.codexdeck.ssh.SshKeys
import cc.cherr.codexdeck.ssh.jumpRoute
import cc.cherr.codexdeck.ssh.validateHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.UUID

class LocalRepository(
    private val dao: StoreDao,
    private val vault: CredentialVault,
) {
    suspend fun hosts(): List<HostRecord> = withContext(Dispatchers.IO) { dao.hosts() }

    suspend fun host(id: String): HostRecord? = withContext(Dispatchers.IO) { dao.host(id) }

    suspend fun identities(): List<IdentityRecord> = withContext(Dispatchers.IO) { dao.identities() }

    suspend fun saveHost(
        draft: HostDraft,
        password: CharArray?,
        replacePassword: Boolean,
    ): HostRecord = withContext(Dispatchers.IO) {
        val port = draft.port.toIntOrNull()
            ?: throw IllegalArgumentException("SSH 端口必须是数字")
        val timeout = draft.connectTimeoutSeconds.toIntOrNull()?.times(1000)
            ?: throw IllegalArgumentException("连接超时必须是数字")
        val keepAlive = draft.keepAliveSeconds.toIntOrNull()
            ?: throw IllegalArgumentException("Keepalive 必须是数字")
        val retry = draft.retryCount.toIntOrNull()
            ?: throw IllegalArgumentException("重试次数必须是数字")
        val current = draft.id?.let(dao::host)
        val now = System.currentTimeMillis()
        val record = (current ?: HostRecord()).apply {
            id = current?.id ?: UUID.randomUUID().toString()
            label = draft.label.trim()
            groupName = draft.groupName.trim()
            tags = draft.tags.split(',').joinToString(",") { it.trim() }.trim(',')
            hostname = draft.hostname.trim().removeSurrounding("[", "]").lowercase()
            this.port = port
            username = draft.username.trim()
            authType = draft.authType
            identityId = draft.identityId
            jumpHostId = draft.jumpHostId?.takeIf(String::isNotBlank)
            codexExecutable = draft.codexExecutable.trim().ifBlank { "codex" }
            connectTimeoutMs = timeout
            keepAliveSeconds = keepAlive
            retryCount = retry
            favorite = draft.favorite
            createdAt = current?.createdAt ?: now
            updatedAt = now
            encryptedPassword = current?.encryptedPassword ?: ByteArray(0)
        }
        validateHost(record)?.let { throw IllegalArgumentException(it) }

        val allHosts = dao.hosts().filter { it.id != record.id } + record
        allHosts.associateBy { it.id }.values.forEach { candidate ->
            jumpRoute(candidate, allHosts.associateBy(HostRecord::id)::get)
        }
        if (record.jumpHostId == record.id) throw IllegalArgumentException("服务器不能以自己作为跳板机")
        if (record.authType == "key" && dao.identity(record.identityId) == null) {
            throw IllegalArgumentException("所选 SSH 身份不存在")
        }
        if (record.authType == "password" && replacePassword) {
            val chars = password ?: throw IllegalArgumentException("请输入登录密码")
            val bytes = chars.concatToString().toByteArray(Charsets.UTF_8)
            try {
                record.encryptedPassword = vault.encrypt("host-password:${record.id}", bytes)
            } finally {
                bytes.fill(0)
                chars.fill('\u0000')
            }
        }
        dao.saveHost(record)
        record
    }

    suspend fun deleteHost(id: String) = withContext(Dispatchers.IO) {
        require(dao.hosts().none { it.jumpHostId == id }) { "请先移除其他服务器对该跳板机的引用" }
        dao.deleteHost(id)
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        dao.toggleFavorite(id, System.currentTimeMillis())
    }

    suspend fun markUsed(id: String) = withContext(Dispatchers.IO) {
        dao.markHostUsed(id, System.currentTimeMillis())
    }

    suspend fun importIdentity(
        label: String,
        pem: ByteArray,
        passphrase: CharArray,
    ): IdentityRecord = withContext(Dispatchers.IO) {
        var privateBytes = pem
        try {
            SshKeys.configure()
            SSHClient().use { client ->
                val key = SshKeys.load(client, privateBytes, passphrase)
                val public = key.getPublic()
                val id = UUID.randomUUID().toString()
                IdentityRecord().apply {
                    this.id = id
                    this.label = label.trim().ifBlank { "SSH Key" }
                    algorithm = SshKeys.algorithm(public)
                    fingerprint = SshKeys.fingerprint(public)
                    publicKey = SshKeys.publicKey(public)
                    encryptedPrivateKey = vault.encrypt("identity:$id", privateBytes)
                    createdAt = System.currentTimeMillis()
                }.also(dao::insertIdentity)
            }
        } finally {
            privateBytes.fill(0)
            passphrase.fill('\u0000')
        }
    }

    suspend fun generateIdentity(
        label: String,
        generation: IdentityGeneration,
        passphrase: CharArray,
    ): IdentityRecord = withContext(Dispatchers.IO) {
        var privateBytes: ByteArray? = null
        try {
            val type = when (generation) {
                IdentityGeneration.ED25519 -> SshKeys.GenerationType.ED25519
                IdentityGeneration.RSA3072 -> SshKeys.GenerationType.RSA3072
                IdentityGeneration.ECDSA_P256 -> SshKeys.GenerationType.ECDSA_P256
            }
            privateBytes = SshKeys.generate(type, passphrase)
            SSHClient().use { client ->
                val public = SshKeys.load(client, privateBytes, passphrase).getPublic()
                val id = UUID.randomUUID().toString()
                IdentityRecord().apply {
                    this.id = id
                    this.label = label.trim().ifBlank { type.label }
                    algorithm = SshKeys.algorithm(public)
                    fingerprint = SshKeys.fingerprint(public)
                    publicKey = SshKeys.publicKey(public)
                    encryptedPrivateKey = vault.encrypt("identity:$id", privateBytes)
                    createdAt = System.currentTimeMillis()
                }.also(dao::insertIdentity)
            }
        } finally {
            privateBytes?.fill(0)
            passphrase.fill('\u0000')
        }
    }

    suspend fun deleteIdentity(id: String) = withContext(Dispatchers.IO) {
        require(dao.identityUsers(id) == 0) { "此身份仍被服务器引用" }
        dao.deleteIdentity(id)
    }

    suspend fun publicKey(id: String): String = withContext(Dispatchers.IO) {
        val identity = requireNotNull(dao.identity(id)) { "SSH 身份不存在" }
        identity.publicKey ?: run {
            val bytes = vault.decrypt("identity:$id", identity.encryptedPrivateKey)
            try {
                SSHClient().use { client ->
                    val key = SshKeys.loadWithPassphraseRequest(client, bytes) { null }.getPublic()
                    SshKeys.publicKey(key).also { dao.savePublicKey(id, it) }
                }
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun cacheThreads(hostId: String, threads: List<CodexThread>) = withContext(Dispatchers.IO) {
        dao.deleteCachedThreads(hostId)
        if (threads.isNotEmpty()) {
            dao.saveCachedThreads(threads.map { thread ->
                CachedThreadRecord().apply {
                    this.hostId = hostId
                    threadId = thread.id
                    title = thread.title
                    cwd = thread.cwd
                    preview = thread.preview
                    status = thread.status.type
                    updatedAt = thread.updatedAt
                    lastOpenedAt = 0
                }
            })
        }
    }

    suspend fun cachedThreads(hostId: String): List<CachedThreadRecord> =
        withContext(Dispatchers.IO) { dao.cachedThreads(hostId) }
}
