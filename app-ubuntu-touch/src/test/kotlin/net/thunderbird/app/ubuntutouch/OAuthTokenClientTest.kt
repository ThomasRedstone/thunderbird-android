package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.junit.Test

class OAuthTokenClientTest {
    private val configuration = OAuthConfiguration(
        "synthetic-client",
        listOf("mail"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )
    private val authorization = OAuthCallback.Authorized("private-code", "a".repeat(43), configuration.redirectUri)
    private val success = """
        {"access_token":"private-access","token_type":"Bearer","expires_in":3600
        ,"refresh_token":"private-refresh"}
    """.trimIndent()

    @Test
    fun `exchange posts code PKCE client and exact redirect then clears response bytes`() = runBlocking {
        var now = 1000L
        var sent: Map<String, String>? = null
        val bytes = success.toByteArray()
        val testSubject = OAuthTokenClient(
            OAuthTokenHttp { endpoint, parameters ->
                assertThat(endpoint.toASCIIString()).isEqualTo(configuration.tokenEndpoint)
                sent = parameters
                now += 5000
                OAuthHttpReply(200, "application/json; charset=UTF-8", bytes)
            },
        ) { now }
        val result = testSubject.exchange(configuration, authorization)
        assertThat(sent).isEqualTo(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "synthetic-client",
                "code" to "private-code",
                "code_verifier" to authorization.verifier,
                "redirect_uri" to configuration.redirectUri,
            ),
        )
        assertThat(result.accessToken).isEqualTo("private-access")
        assertThat(result.refreshToken).isEqualTo("private-refresh")
        assertThat(result.expiresAtMillis).isEqualTo(3601000L)
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `refresh supports rotation and preserves old refresh token only when omitted`() = runBlocking {
        var response = success.replace(",\"refresh_token\":\"private-refresh\"", "")
        val old = OAuthTokens("expired-access", "old-refresh", 0)
        val testSubject = OAuthTokenClient(
            OAuthTokenHttp { _, parameters ->
                assertThat(parameters).isEqualTo(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "client_id" to "synthetic-client",
                        "refresh_token" to "old-refresh",
                    ),
                )
                OAuthHttpReply(200, "application/json", response.toByteArray())
            },
        ) { 0 }
        assertThat(testSubject.refresh(configuration, old).refreshToken).isEqualTo("old-refresh")
        response = success
        assertThat(testSubject.refresh(configuration, old).refreshToken).isEqualTo("private-refresh")
    }

    @Test
    fun `provider errors are classified without descriptions causes or retained bytes`() = runBlocking {
        listOf(
            Triple(400, "invalid_grant", OAuthTokenFailure.REAUTH_REQUIRED),
            Triple(400, "invalid_client", OAuthTokenFailure.CONFIGURATION),
            Triple(400, "invalid_scope", OAuthTokenFailure.CONFIGURATION),
            Triple(429, "private-error", OAuthTokenFailure.UNAVAILABLE),
            Triple(503, "private-error", OAuthTokenFailure.UNAVAILABLE),
            Triple(302, "private-error", OAuthTokenFailure.INVALID_RESPONSE),
            Triple(200, "invalid_grant", OAuthTokenFailure.INVALID_RESPONSE),
        ).forEach { (status, code, reason) ->
            val bytes = """{"error":"$code","error_description":"private-description"}""".toByteArray()
            val testSubject =
                OAuthTokenClient(OAuthTokenHttp { _, _ -> OAuthHttpReply(status, "application/json", bytes) })
            assertFailure(reason) { testSubject.exchange(configuration, authorization) }
            assertThat(bytes.all { it == 0.toByte() }).isTrue()
        }
    }

    @Test
    fun `malformed duplicate escaped duplicate invalid token and expiry responses fail closed`() = runBlocking {
        val responses = listOf(
            "not JSON private-access", "[]", "{}", success + "trailing",
            success.replace("\"Bearer\"", "\"MAC\""),
            success.replace("3600", "0"), success.replace("3600", "-1"),
            success.replace("3600", "9223372036854775807"),
            success.replace("private-access", "private\\naccess"),
            success.replace("private-refresh", ""),
            success.replace("\"access_token\":", "\"access_token\":\"first\",\"access_token\":"),
            success.replace("\"access_token\":", "\"access_token\":\"first\",\"access_\\u0074oken\":"),
            success.replace("private-access", "x".repeat(16385)),
        ).map { it.toByteArray() } + listOf(byteArrayOf(-1), ByteArray(MAX_TOKEN_RESPONSE + 1))
        responses.forEach { bytes ->
            val testSubject =
                OAuthTokenClient(OAuthTokenHttp { _, _ -> OAuthHttpReply(200, "application/json", bytes) })
            assertFailure(OAuthTokenFailure.INVALID_RESPONSE) { testSubject.exchange(configuration, authorization) }
            assertThat(bytes.all { it == 0.toByte() }).isTrue()
        }
    }

    @Test
    fun `unknown nested extensions are ignored without replacing known fields`() = runBlocking {
        val json = success.dropLast(1) + ",\"extension\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + "}"
        val testSubject =
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    OAuthHttpReply(200, "application/json", json.toByteArray())
                },
            ) { 0 }
        assertThat(testSubject.exchange(configuration, authorization).accessToken).isEqualTo("private-access")
    }

    @Test
    fun `cancellation remains cancellation and missing refresh never contacts endpoint`() = runBlocking {
        val entered = Channel<Unit>(1)
        val testSubject = OAuthTokenClient(
            OAuthTokenHttp { _, _ ->
                entered.send(Unit)
                awaitCancellation()
            },
        )
        val pending = async { testSubject.exchange(configuration, authorization) }
        entered.receive()
        pending.cancelAndJoin()
        assertThat(pending.isCancelled).isTrue()
        val noNetwork = OAuthTokenClient(OAuthTokenHttp { _, _ -> error("Unexpected token request") })
        assertFailure(OAuthTokenFailure.REAUTH_REQUIRED) {
            noNetwork.refresh(configuration, OAuthTokens("access", null, 0))
        }
    }

    @Test
    fun `unsafe endpoint and mismatched exchange are rejected before sending secrets`() = runBlocking {
        val testSubject = OAuthTokenClient(OAuthTokenHttp { _, _ -> error("Unsafe request sent") })
        listOf(
            configuration.copy(tokenEndpoint = "http://login.example.invalid/token"),
            configuration.copy(clientId = "x".repeat(4097)),
            configuration.copy(tokenEndpoint = "https://user@login.example.invalid/token"),
            configuration.copy(tokenEndpoint = "https://login.example.invalid/token?query=private"),
            configuration.copy(redirectUri = "http://127.0.0.1:49153/callback"),
        ).forEach {
            assertThat(
                runCatching {
                    testSubject.exchange(it, authorization)
                }.exceptionOrNull() is IllegalArgumentException,
            ).isTrue()
        }
    }

    @Test
    fun `transport errors and invalid content type expose generic failures`() = runBlocking {
        val failing = OAuthTokenClient(OAuthTokenHttp { _, _ -> throw IOException("private-code") })
        assertFailure(OAuthTokenFailure.UNAVAILABLE) { failing.exchange(configuration, authorization) }
        val testSubject =
            OAuthTokenClient(OAuthTokenHttp { _, _ -> OAuthHttpReply(200, "text/html", success.toByteArray()) })
        assertFailure(OAuthTokenFailure.INVALID_RESPONSE) { testSubject.exchange(configuration, authorization) }
    }

    private suspend fun assertFailure(reason: OAuthTokenFailure, operation: suspend () -> OAuthTokens) {
        val failure = runCatching { operation() }.exceptionOrNull() as OAuthTokenException
        assertThat(failure.reason).isEqualTo(reason)
        assertThat(failure.message).isEqualTo("OAuth token request: ${reason.name}")
        assertThat(failure.cause).isNull()
        assertThat(failure.suppressed.size).isEqualTo(0)
    }
}
