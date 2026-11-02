package cc.cherr.codexdeck.ssh

import java.io.InputStream

/**
 * Normalizes private-key sources before they enter the existing SSHJ validation and
 * Android Keystore encryption path.
 */
object SshPrivateKeyInput {
    const val MAX_BYTES = 256 * 1024

    fun fromPaste(pasted: String): ByteArray {
        require(pasted.isNotBlank()) { "请粘贴 SSH 私钥" }
        val bytes = (pasted.trim() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            bytes.fill(0)
            throw IllegalArgumentException("私钥不能超过 256 KiB")
        }
        return bytes
    }

    /**
     * Reads at most [MAX_BYTES] plus one sentinel byte so an oversized document can be
     * rejected without buffering the entire file.
     */
    fun readFromStream(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        try {
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                require(count > 0) { "读取私钥失败" }
                size += count
            }
            require(size <= MAX_BYTES) { "私钥不能超过 256 KiB" }
            require(size > 0) { "私钥文件为空" }
            return buffer.copyOf(size)
        } finally {
            buffer.fill(0)
        }
    }
}
