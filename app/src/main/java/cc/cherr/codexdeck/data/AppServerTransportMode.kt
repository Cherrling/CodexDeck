package cc.cherr.codexdeck.data

object AppServerTransportMode {
    const val SSH_LAUNCH = "ssh"
    const val SSH_EXISTING = "ssh-proxy"
    const val WEBSOCKET = "websocket"

    val sshModes = setOf(SSH_LAUNCH, SSH_EXISTING)
    val supportedModes = sshModes + WEBSOCKET
}
