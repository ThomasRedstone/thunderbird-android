package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.junit.Test

class OAuthLoopbackReceiverTest {
    @Test
    fun `real HTTP callback returns code and closes listener without reflecting secrets`() = runBlocking {
        val testSubject = receiver()
        val details = details(testSubject)
        testSubject.use {
            val pending = async { testSubject.await() }
            val response = exchange(details, request(details))
            val result = withTimeout(2000) { pending.await() } as OAuthCallback.Authorized
            assertThat(result.code).isEqualTo("private-code")
            assertThat(result.redirectUri).isEqualTo(details.redirect.toASCIIString())
            assertThat(response).contains("200 OK")
            assertThat(response).contains("Cache-Control: no-store")
            assertThat(response.contains("private-code")).isFalse()
            assertThat(response.contains(details.state)).isFalse()
            assertThat(canConnect(details.redirect.port)).isFalse()
        }
    }

    @Test
    fun `invalid HTTP and state attempts do not consume later valid callback`() = runBlocking {
        val testSubject = receiver()
        val details = details(testSubject)
        testSubject.use {
            val pending = async { testSubject.await() }
            val valid = request(details)
            val invalid = listOf(
                valid.replace("GET ", "POST "),
                valid.replace("HTTP/1.1", "HTTP/1.0"),
                valid.replace("Host: 127.0.0.1", "Host: localhost"),
                valid.replace("\r\n\r\n", "\r\nHost: attacker.invalid\r\n\r\n"),
                valid.replace("\r\n\r\n", "\r\nTransfer-Encoding: chunked\r\n\r\n"),
                valid.replace("\r\n\r\n", "\r\nContent-Length: 1\r\n\r\nx"),
                valid.replace("/oauth2/callback?", "/wrong?"),
                valid.replace(details.state, "wrong"),
                valid.replace("GET /", "GET http://attacker.invalid/"),
                valid.replace("GET /", "GET //attacker.invalid/"),
                valid.replace("Host:", "Host :"),
                valid.replace("\r\n\r\n", "\r\n Folded: value\r\n\r\n"),
                valid.replace("\r\n\r\n", "\r\nExtra: " + "x".repeat(17000) + "\r\n\r\n"),
            )
            invalid.forEach { assertThat(exchange(details, it)).contains("400 Bad Request") }
            assertThat(exchange(details, valid)).contains("200 OK")
            assertThat(withTimeout(2000) { pending.await() } is OAuthCallback.Authorized).isTrue()
        }
    }

    @Test
    fun `denial consumes listener and does not reflect error description`() = runBlocking {
        val testSubject = receiver()
        val details = details(testSubject)
        testSubject.use {
            val pending = async { testSubject.await() }
            val response =
                exchange(
                    details,
                    request(details).replace("code=private-code", "error=access_denied&error_description=private"),
                )
            assertThat(pending.await()).isEqualTo(OAuthCallback.Denied)
            assertThat(response.contains("private")).isFalse()
            assertThat(canConnect(details.redirect.port)).isFalse()
        }
    }

    @Test
    fun `absolute request deadline releases slow client and permits next callback`() = runBlocking {
        val testSubject = receiver(requestTimeout = 100)
        val details = details(testSubject)
        testSubject.use {
            val pending = async { testSubject.await() }
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", details.redirect.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("GET /".toByteArray())
                    assertThat(
                        socket.getInputStream().readBytes().toString(Charsets.US_ASCII),
                    ).contains("400 Bad Request")
                }
            }
            exchange(details, request(details))
            assertThat(pending.await() is OAuthCallback.Authorized).isTrue()
        }
    }

    @Test
    fun `cancellation closes listening socket and partial request socket`() = runBlocking {
        val testSubject = receiver()
        val details = details(testSubject)
        testSubject.use {
            val pending = async { testSubject.await() }
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", details.redirect.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write('G'.code)
                    delay(50)
                    withTimeout(2000) { pending.cancelAndJoin() }
                    assertThat(socket.getInputStream().read()).isEqualTo(-1)
                }
            }
            assertThat(canConnect(details.redirect.port)).isFalse()
        }
    }

    @Test
    fun `total timeout reports generic failure and closes listener`() = runBlocking {
        val testSubject = receiver(totalTimeout = 100, requestTimeout = 50)
        val details = details(testSubject)
        testSubject.use {
            val failure = runCatching { withTimeout(2000) { testSubject.await() } }.exceptionOrNull()
            assertThat(failure is OAuthLoopbackException).isTrue()
            assertThat(failure?.message).isEqualTo("OAuth callback unavailable or timed out")
            assertThat(canConnect(details.redirect.port)).isFalse()
        }
    }

    @Test
    fun `close before awaiting fails safely and repeated close is harmless`() = runBlocking {
        val testSubject = receiver()
        val details = details(testSubject)
        testSubject.close()
        testSubject.close()
        val failure = runCatching { testSubject.await() }.exceptionOrNull()
        assertThat(failure is OAuthLoopbackException).isTrue()
        assertThat(canConnect(details.redirect.port)).isFalse()
    }

    @Test
    fun `configuration failure closes the allocated listener`() {
        var port = 0
        val failure = runCatching {
            OAuthLoopbackReceiver.open(configuration = { redirect ->
                port = redirect.port
                error("Synthetic configuration failure")
            })
        }
        assertThat(failure.isFailure).isTrue()
        assertThat(port > 0).isTrue()
        assertThat(canConnect(port)).isFalse()
    }

    @Test
    fun `overall deadline starts at construction rather than await`() = runBlocking {
        val testSubject = receiver(totalTimeout = 100, requestTimeout = 50)
        val details = details(testSubject)
        testSubject.use {
            delay(150)
            val failure = runCatching { withTimeout(2000) { testSubject.await() } }.exceptionOrNull()
            assertThat(failure is OAuthLoopbackException).isTrue()
            assertThat(canConnect(details.redirect.port)).isFalse()
        }
    }

    @Test
    fun `second waiter is rejected without disrupting original waiter`() = runBlocking {
        val testSubject = receiver()
        val details = details(testSubject)
        testSubject.use {
            val pending = async { testSubject.await() }
            kotlinx.coroutines.yield()
            assertThat(runCatching { testSubject.await() }.exceptionOrNull() is IllegalStateException).isTrue()
            exchange(details, request(details))
            assertThat(pending.await() is OAuthCallback.Authorized).isTrue()
        }
    }

    private fun receiver(totalTimeout: Int = 5000, requestTimeout: Int = 500): OAuthLoopbackReceiver =
        OAuthLoopbackReceiver.open(
            configuration = { redirect ->
                OAuthConfiguration(
                    "synthetic-client",
                    listOf("mail"),
                    "https://login.example.invalid/authorize",
                    "https://login.example.invalid/token",
                    redirect.toASCIIString(),
                )
            },
            totalTimeoutMillis = totalTimeout,
            requestTimeoutMillis = requestTimeout,
        )

    private fun details(receiver: OAuthLoopbackReceiver): Details {
        val parameters = receiver.authorizationUri.rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
        }
        return Details(URI(parameters.getValue("redirect_uri")), parameters.getValue("state"))
    }

    private fun request(details: Details): String =
        "GET ${details.redirect.rawPath}?state=${details.state}&code=private-code HTTP/1.1\r\n" +
            "Host: 127.0.0.1:${details.redirect.port}\r\n\r\n"

    private suspend fun exchange(details: Details, request: String): String = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", details.redirect.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
        }
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
    }.isSuccess

    private class Details(val redirect: URI, val state: String)
}
