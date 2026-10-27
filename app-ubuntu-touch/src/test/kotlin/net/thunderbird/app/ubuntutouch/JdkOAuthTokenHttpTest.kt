package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

class JdkOAuthTokenHttpTest {
    @Test
    fun `profile GET sends bearer only in header and refuses redirect forwarding`() = runBlocking {
        val redirects = AtomicInteger()
        Fixture { exchange ->
            if (exchange.requestURI.path == "/target") redirects.incrementAndGet()
            assertThat(exchange.requestMethod).isEqualTo("GET")
            assertThat(exchange.requestURI.rawQuery).isEqualTo(null)
            assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer private-access")
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
        }.use { fixture ->
            JdkOAuthTokenHttp(trustedContext).use { testSubject ->
                assertThat(testSubject.get(fixture.uri, "private-access").status).isEqualTo(302)
                assertThat(redirects.get()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `verified HTTPS profile uses scoped fixture trust while default trust rejects the endpoint`() = runBlocking {
        Fixture { exchange ->
            assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer access")
            val bytes = """{"sub":"subject","email":"fixture@example.invalid","email_verified":true}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }.use { fixture ->
            val policy = OAuthIdentityPolicy(fixture.uri.toASCIIString())
            val tokens = OAuthTokens("access", "refresh", 1)
            JdkOAuthTokenHttp().use { transport ->
                assertThat(
                    runCatching {
                        HttpsOAuthIdentityVerifier(transport).verify(policy, tokens)
                    }.isFailure,
                ).isTrue()
            }
            JdkOAuthTokenHttp(trustedContext).use { transport ->
                val testSubject = HttpsOAuthIdentityVerifier(transport)
                testSubject.verify(
                    policy,
                    tokens,
                ).check(OAuthIdentityRequirement(policy, "fixture@example.invalid"), tokens)
            }
        }
    }

    @Test
    fun `verified TLS POST uses form encoding and never follows redirect`() = runBlocking {
        val redirects = AtomicInteger()
        Fixture { exchange ->
            if (exchange.requestURI.path == "/target") redirects.incrementAndGet()
            assertThat(exchange.requestMethod).isEqualTo("POST")
            assertThat(exchange.requestHeaders.getFirst("Content-Type")).isEqualTo("application/x-www-form-urlencoded")
            val fields = exchange.requestBody.readBytes().toString(Charsets.UTF_8).split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
            }
            assertThat(fields).isEqualTo(mapOf("code" to "private +&=λ", "client_id" to "synthetic"))
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
        }.use { fixture ->
            JdkOAuthTokenHttp(trustedContext).use { testSubject ->
                val result = testSubject.post(fixture.uri, mapOf("code" to "private +&=λ", "client_id" to "synthetic"))
                assertThat(result.status).isEqualTo(302)
                assertThat(redirects.get()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `token client exchanges code through verified HTTPS transport`() = runBlocking {
        Fixture { exchange ->
            val fields = exchange.requestBody.readBytes().toString(Charsets.UTF_8).split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
            }
            assertThat(fields["grant_type"]).isEqualTo("authorization_code")
            assertThat(fields["code_verifier"]).isEqualTo("a".repeat(43))
            assertThat(fields["code"]).isEqualTo("synthetic-code")
            val bytes = """{"access_token":"a","token_type":"Bearer","expires_in":3600,"refresh_token":"r"}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }.use { fixture ->
            JdkOAuthTokenHttp(trustedContext).use { transport ->
                val configuration = net.thunderbird.core.common.oauth.OAuthConfiguration(
                    "synthetic-client",
                    listOf("mail"),
                    "https://login.example.invalid/auth",
                    fixture.uri.toASCIIString(),
                    "http://127.0.0.1:49152/callback",
                )
                val testSubject = OAuthTokenClient(transport) { 0 }
                val result = testSubject.exchange(
                    configuration,
                    OAuthCallback.Authorized("synthetic-code", "a".repeat(43), configuration.redirectUri),
                )
                assertThat(result.accessToken).isEqualTo("a")
                assertThat(result.refreshToken).isEqualTo("r")
                assertThat(result.expiresAtMillis).isEqualTo(3600000L)
            }
        }
    }

    @Test
    fun `default trust rejects self signed endpoint and injected trust still checks hostname`() = runBlocking {
        Fixture { exchange -> exchange.sendResponseHeaders(200, -1) }.use { fixture ->
            JdkOAuthTokenHttp().use { testSubject ->
                val failure = runCatching {
                    testSubject.post(fixture.uri, emptyMap())
                }.exceptionOrNull() as OAuthTokenException
                assertThat(failure.reason).isEqualTo(OAuthTokenFailure.UNAVAILABLE)
            }
            JdkOAuthTokenHttp(trustedContext).use { testSubject ->
                val wrongHost = URI(fixture.uri.toString().replace("localhost", "127.0.0.1"))
                val failure = runCatching {
                    testSubject.post(wrongHost, emptyMap())
                }.exceptionOrNull() as OAuthTokenException
                assertThat(failure.reason).isEqualTo(OAuthTokenFailure.UNAVAILABLE)
            }
        }
    }

    @Test
    fun `actual chunked response bytes enforce body cap`() = runBlocking {
        Fixture { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write(ByteArray(MAX_TOKEN_RESPONSE + 1) { 'x'.code.toByte() })
            } catch (_: IOException) {
                // A client rejecting the oversized body can close before the fixture finishes writing.
            }
        }.use { fixture ->
            JdkOAuthTokenHttp(trustedContext).use { testSubject ->
                val failure = runCatching {
                    testSubject.post(fixture.uri, emptyMap())
                }.exceptionOrNull() as OAuthTokenException
                assertThat(failure.reason).isEqualTo(OAuthTokenFailure.INVALID_RESPONSE)
            }
        }
    }

    @Test
    fun `cancelling after response headers releases a stalled body reader`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('x'.code)
            exchange.responseBody.flush()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }.use { fixture ->
            try {
                JdkOAuthTokenHttp(trustedContext).use { testSubject ->
                    val pending = async { testSubject.post(fixture.uri, emptyMap()) }
                    withContext(Dispatchers.IO) { check(entered.await(3, TimeUnit.SECONDS)) }
                    withTimeout(2000) { pending.cancelAndJoin() }
                    assertThat(pending.isCancelled).isTrue()
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `overall timeout includes a body stalled after headers`() = runBlocking {
        val release = CountDownLatch(1)
        Fixture { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('x'.code)
            exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        }.use { fixture ->
            try {
                JdkOAuthTokenHttp(trustedContext, timeoutMillis = 1000).use { testSubject ->
                    val failure = runCatching {
                        withTimeout(3000) { testSubject.post(fixture.uri, emptyMap()) }
                    }.exceptionOrNull()
                    assertThat(failure is CancellationException).isTrue()
                }
            } finally {
                release.countDown()
            }
        }
    }

    private class Fixture(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext)
            this.executor = this@Fixture.executor
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val uri: URI = URI("https://localhost:${server.address.port}/token")
        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        private val directory = Files.createTempDirectory("oauth-token-tls-")
        private lateinit var serverContext: SSLContext
        private lateinit var trustedContext: SSLContext

        @BeforeClass
        @JvmStatic
        fun certificate() {
            val path = directory.resolve("fixture.p12")
            val password = "synthetic-fixture"
            val process = ProcessBuilder(
                "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", path.toString(),
                "-storepass", password, "-keypass", password, "-noprompt",
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val store = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(path).use { load(it, password.toCharArray()) }
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, password.toCharArray())
            }
            serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("fixture", store.getCertificate("fixture"))
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trust)
            }
            trustedContext = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            check(directory.toFile().deleteRecursively())
        }
    }
}
