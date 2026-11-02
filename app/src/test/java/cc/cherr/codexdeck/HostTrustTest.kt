package cc.cherr.codexdeck

import cc.cherr.codexdeck.ssh.HostChallenge
import cc.cherr.codexdeck.ssh.HostPin
import cc.cherr.codexdeck.ssh.HostTrust
import cc.cherr.codexdeck.ssh.SshKeys
import cc.cherr.codexdeck.ssh.TrustDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPairGenerator

class HostTrustTest {
    @Test
    fun matchingStoredFingerprintNeedsNoPrompt() {
        val publicKey = rsaKeyPair().public
        val pin = pin(publicKey)
        var prompts = 0
        val trust = HostTrust("example.test", 22, { pin }, { error("must not save") }) {
            prompts++
            TrustDecision.CANCEL
        }

        assertTrue(trust.verify("example.test", 22, publicKey))
        assertEquals(0, prompts)
    }

    @Test
    fun changedFingerprintPromptsAndCancelRejectsWithoutReplacingPin() {
        val previous = pin(rsaKeyPair().public)
        val presentedKey = rsaKeyPair().public
        val presented = pin(presentedKey)
        var challenge: HostChallenge? = null
        var saved: HostPin? = null
        val trust = HostTrust("example.test", 22, { previous }, { saved = it }) {
            challenge = it
            TrustDecision.CANCEL
        }

        assertFalse(trust.verify("example.test", 22, presentedKey))
        assertEquals(previous, challenge?.previous)
        assertEquals(presented, challenge?.presented)
        assertNull(saved)
    }

    @Test
    fun saveDecisionReplacesOnlyAfterExplicitApproval() {
        val presentedKey = rsaKeyPair().public
        val presented = pin(presentedKey)
        var saved: HostPin? = null
        val trust = HostTrust("example.test", 2222, { null }, { saved = it }) {
            TrustDecision.SAVE
        }

        assertTrue(trust.verify("example.test", 2222, presentedKey))
        assertEquals(presented, saved)
    }

    private fun pin(publicKey: java.security.PublicKey) =
        HostPin(SshKeys.algorithm(publicKey), SshKeys.fingerprint(publicKey))

    private fun rsaKeyPair() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    companion object {
        @JvmStatic
        @BeforeClass
        fun configureCrypto() {
            SshKeys.configure()
        }
    }
}
