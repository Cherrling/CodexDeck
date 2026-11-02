package cc.cherr.codexdeck.ssh

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

class HostTrust(
    private val requestedHostname: String,
    private val requestedPort: Int,
    private val read: () -> HostPin?,
    private val save: (HostPin) -> Unit,
    private val ask: (HostChallenge) -> TrustDecision,
) : HostKeyVerifier {
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        read()?.let { listOf(it.algorithm) }.orEmpty()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val presented = HostPin(SshKeys.algorithm(key), SshKeys.fingerprint(key))
        val previous = read()
        if (presented == previous) return true
        return when (ask(HostChallenge(requestedHostname, requestedPort, presented, previous))) {
            TrustDecision.CANCEL -> false
            TrustDecision.ONCE -> true
            TrustDecision.SAVE -> {
                save(presented)
                true
            }
        }
    }
}
