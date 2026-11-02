package cc.cherr.codexdeck.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object SshKeys {
    @Synchronized
    fun configure() {
        if (Security.getProvider("BC") !is BouncyCastleProvider) {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
        SecurityUtils.setSecurityProvider("BC")
    }

    fun load(client: SSHClient, pem: ByteArray, passphrase: CharArray): KeyProvider =
        loadWithPassphraseRequest(client, pem) { passphrase.copyOf() }

    /**
     * SSHJ asks for a password only when this exact key is encrypted. Authentication errors are
     * deliberately not used to infer that a key needs a passphrase.
     */
    fun loadWithPassphraseRequest(
        client: SSHClient,
        pem: ByteArray,
        requestPassphrase: () -> CharArray?,
    ): KeyProvider {
        require(pem.size in 1..(256 * 1024)) { "私钥文件为空或超过 256 KiB" }
        var requestedPassword: CharArray? = null
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray? =
                requestPassphrase().also { requestedPassword = it }

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
        try {
            val text = pem.toString(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trim() + "\n"
            val normalized = normalizePkcs8(text) { finder.reqPassword(null) }
            val provider = client.loadKeys(normalized, null, finder)
            provider.getPrivate()
            provider.getPublic()
            return provider
        } finally {
            requestedPassword?.fill('\u0000')
        }
    }

    private fun normalizePkcs8(text: String, request: () -> CharArray?): String {
        if (!text.startsWith("-----BEGIN PRIVATE KEY-----") &&
            !text.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----")
        ) {
            return text
        }
        val info = org.bouncycastle.openssl.PEMParser(text.reader()).use { parser ->
            when (val parsed = parser.readObject()) {
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> parsed
                is PKCS8EncryptedPrivateKeyInfo -> {
                    val password = request() ?: throw java.io.IOException("Private key unlock cancelled")
                    parsed.decryptPrivateKeyInfo(
                        JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider("BC")
                            .build(password),
                    )
                }
                else -> throw java.io.IOException("Unsupported PKCS#8 private key")
            }
        }
        val key = org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(info)
        val label = when (key) {
            is org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters -> "RSA PRIVATE KEY"
            is org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters,
            is org.bouncycastle.crypto.params.ECPrivateKeyParameters -> "OPENSSH PRIVATE KEY"
            else -> throw java.io.IOException("Unsupported PKCS#8 key algorithm")
        }
        val encoded = org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.encodePrivateKey(key)
        return try {
            "-----BEGIN $label-----\n" +
                Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(encoded) +
                "\n-----END $label-----\n"
        } finally {
            encoded.fill(0)
        }
    }

    enum class GenerationType(val label: String, val jcaName: String) {
        ED25519("Ed25519", "Ed25519"),
        RSA3072("RSA 3072", "RSA"),
        ECDSA_P256("ECDSA P-256", "EC"),
    }

    /** Caller must wipe the returned bytes after encrypting them. */
    fun generate(type: GenerationType, passphrase: CharArray): ByteArray {
        configure()
        val random = java.security.SecureRandom()
        val generator = java.security.KeyPairGenerator.getInstance(type.jcaName, "BC")
        when (type) {
            GenerationType.RSA3072 -> generator.initialize(3072, random)
            GenerationType.ECDSA_P256 -> generator.initialize(ECGenParameterSpec("secp256r1"), random)
            GenerationType.ED25519 -> Unit
        }
        val pair = generator.generateKeyPair()
        return encodePkcs8(pair.private, passphrase)
    }

    fun exportPrivate(key: java.security.PrivateKey, passphrase: CharArray): ByteArray {
        require(passphrase.isEmpty() || passphrase.size >= 8) { "私钥口令至少需要 8 个字符" }
        configure()
        return encodePkcs8(key, passphrase)
    }

    private fun encodePkcs8(key: java.security.PrivateKey, passphrase: CharArray): ByteArray {
        val encryptor = if (passphrase.isEmpty()) {
            null
        } else {
            JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                .setProvider("BC")
                .setRandom(java.security.SecureRandom())
                .setPassword(passphrase)
                .setPRF(PKCS8Generator.PRF_HMACSHA256)
                .setIterationCount(210_000)
                .build()
        }
        val pem = JcaPKCS8Generator(key, encryptor).generate()
        return try {
            (
                "-----BEGIN ${pem.type}-----\n" +
                    Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(pem.content) +
                    "\n-----END ${pem.type}-----\n"
                ).toByteArray(Charsets.UTF_8)
        } finally {
            pem.content.fill(0)
        }
    }

    fun publicKey(key: PublicKey): String =
        "${algorithm(key)} ${Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData)}"

    fun fingerprint(key: PublicKey): String {
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wire)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun algorithm(key: PublicKey): String = KeyType.fromKey(key).toString()
}
