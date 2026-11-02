package cc.cherr.codexdeck.data

data class HostDraft(
    val id: String? = null,
    val label: String = "",
    val groupName: String = "",
    val tags: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: String = "key",
    val identityId: String? = null,
    val jumpHostId: String? = null,
    val codexExecutable: String = "codex",
    val appServerTransport: String = AppServerTransportMode.SSH_EXISTING,
    val appServerUrl: String = "",
    val webSocketViaSsh: Boolean = false,
    val allowInsecureWebSocket: Boolean = false,
    val connectTimeoutSeconds: String = "15",
    val keepAliveSeconds: String = "60",
    val retryCount: String = "1",
    val favorite: Boolean = false,
) {
    companion object {
        fun from(record: HostRecord): HostDraft = HostDraft(
            id = record.id,
            label = record.label,
            groupName = record.groupName,
            tags = record.tags,
            hostname = record.hostname,
            port = record.port.toString(),
            username = record.username,
            authType = record.authType,
            identityId = record.identityId,
            jumpHostId = record.jumpHostId,
            codexExecutable = record.codexExecutable,
            appServerTransport = record.appServerTransport,
            appServerUrl = record.appServerUrl,
            webSocketViaSsh = record.webSocketViaSsh,
            allowInsecureWebSocket = record.allowInsecureWebSocket,
            connectTimeoutSeconds = (record.connectTimeoutMs / 1000).toString(),
            keepAliveSeconds = record.keepAliveSeconds.toString(),
            retryCount = record.retryCount.toString(),
            favorite = record.favorite,
        )
    }
}

enum class IdentityGeneration {
    ED25519,
    RSA3072,
    ECDSA_P256,
}
