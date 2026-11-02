package cc.cherr.codexdeck

import cc.cherr.codexdeck.ssh.SshKeys
import cc.cherr.codexdeck.ssh.SshPrivateKeyInput
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream

class SshPrivateKeyInputTest {
    @Test
    fun pastedKeyIsTrimmedAndKeepsTrailingNewline() {
        val bytes = SshPrivateKeyInput.fromPaste("  -----BEGIN TEST-----  \r\n\r\n")

        assertEquals("-----BEGIN TEST-----\n", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun pastedGeneratedPrivateKeyLoadsThroughExistingParser() {
        val generated = SshKeys.generate(SshKeys.GenerationType.ED25519, charArrayOf())
        val pasted = generated.toString(Charsets.UTF_8)
        generated.fill(0)

        val normalized = SshPrivateKeyInput.fromPaste("  $pasted\n")
        try {
            SSHClient().use { client ->
                val key = SshKeys.load(client, normalized, charArrayOf())
                assertEquals("ssh-ed25519", SshKeys.publicKey(key.getPublic()).substringBefore(' '))
            }
        } finally {
            normalized.fill(0)
        }
    }

    @Test
    fun fileAndPasteInputsRejectMoreThan256KiB() {
        val oversized = ByteArray(SshPrivateKeyInput.MAX_BYTES + 1) { 'a'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            SshPrivateKeyInput.fromPaste(oversized.toString(Charsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshPrivateKeyInput.readFromStream(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun emptyFileIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SshPrivateKeyInput.readFromStream(ByteArrayInputStream(byteArrayOf()))
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun configureCrypto() {
            SshKeys.configure()
        }
    }
}
