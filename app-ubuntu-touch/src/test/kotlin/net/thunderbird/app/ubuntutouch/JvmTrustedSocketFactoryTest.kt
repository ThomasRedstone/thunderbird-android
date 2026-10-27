package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.ssl.CertificateChainExtractor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

class JvmTrustedSocketFactoryTest {
    @Test
    fun `trusted hostname works with direct TLS and STARTTLS wrapping`() {
        val testSubject = JvmTrustedSocketFactory(systemTrustManager(trustedCertificates))
        assertThat(handshake(testSubject, "localhost", wrapped = false).isSuccess).isTrue()
        assertThat(handshake(testSubject, "localhost", wrapped = true).isSuccess).isTrue()
    }

    @Test
    fun `trusted chain with wrong hostname is rejected`() {
        val testSubject = JvmTrustedSocketFactory(systemTrustManager(trustedCertificates))
        assertThat(handshake(testSubject, "wrong.example.invalid", wrapped = false).isSuccess).isFalse()
        assertThat(handshake(testSubject, "wrong.example.invalid", wrapped = true).isSuccess).isFalse()
    }

    @Test
    fun `system trust rejects unknown self signed certificate`() {
        val testSubject = JvmTrustedSocketFactory()
        val result = handshake(testSubject, "localhost", wrapped = false)
        assertThat(result.isSuccess).isFalse()
        assertThat(CertificateChainExtractor.extract(result.exceptionOrNull()!!)).isNotNull()
    }

    private fun handshake(testSubject: JvmTrustedSocketFactory, host: String, wrapped: Boolean): Result<Unit> {
        val loopback = InetAddress.getLoopbackAddress()
        return (serverContext.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket).use { server ->
            val peer = CompletableFuture.supplyAsync {
                runCatching {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = TIMEOUT_MS
                        socket.startHandshake()
                    }
                }
            }
            val result = runCatching {
                val plain = if (wrapped) {
                    Socket().apply {
                        connect(InetSocketAddress(loopback, server.localPort))
                    }
                } else {
                    null
                }
                testSubject.createSocket(plain, host, server.localPort, null).use { socket ->
                    socket.soTimeout = TIMEOUT_MS
                    if (!wrapped) socket.connect(InetSocketAddress(loopback, server.localPort), TIMEOUT_MS)
                    (socket as SSLSocket).startHandshake()
                }
            }
            peer.get(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            result
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10000
        private val directory = Files.createTempDirectory("mail-tls-test-")
        private lateinit var serverContext: SSLContext
        private lateinit var trustedCertificates: KeyStore

        @BeforeClass
        @JvmStatic
        fun createTestCertificate() {
            val path = directory.resolve("server.p12")
            // Ephemeral fixture credentials, never used by an account or external service.
            val password = "fixture-password"
            val process = ProcessBuilder(
                "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", path.toString(),
                "-storepass", password, "-keypass", password, "-noprompt",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Test certificate generation timed out" }
            check(process.exitValue() == 0) { "Test certificate generation failed" }
            val keys = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(path).use { load(it, password.toCharArray()) }
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keys, password.toCharArray())
            }
            serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            trustedCertificates = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("fixture", keys.getCertificate("server"))
            }
        }

        @AfterClass
        @JvmStatic
        fun removeTestCertificate() {
            directory.toFile().deleteRecursively()
        }
    }
}
