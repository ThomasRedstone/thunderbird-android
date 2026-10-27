package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.net.URLDecoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.junit.Test

class OAuthAuthorizationAttemptTest {
    private val configuration = OAuthConfiguration(
        clientId = "synthetic client&value",
        scopes = listOf("mail.read", "mail.send"),
        authorizationEndpoint = "https://login.example.invalid/authorize",
        tokenEndpoint = "https://login.example.invalid/token",
        redirectUri = "http://127.0.0.1:49152/callback",
    )

    @Test
    fun `PKCE S256 matches RFC 7636 appendix B`() {
        assertThat(pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `authorization request round trips values and binds returned code to verifier`() {
        val testSubject = OAuthAuthorizationAttempt.create(configuration)
        val parameters = parameters(testSubject)
        assertThat(parameters["client_id"]).isEqualTo(configuration.clientId)
        assertThat(parameters["scope"]).isEqualTo("mail.read mail.send")
        assertThat(parameters["redirect_uri"]).isEqualTo(configuration.redirectUri)
        assertThat(parameters["response_type"]).isEqualTo("code")
        assertThat(parameters["code_challenge_method"]).isEqualTo("S256")
        val result = testSubject.accept(callback(testSubject)) as OAuthCallback.Authorized
        assertThat(result.code).isEqualTo("synthetic-code")
        assertThat(result.redirectUri).isEqualTo(configuration.redirectUri)
        assertThat(result.verifier.matches(Regex("[A-Za-z0-9_-]{43}"))).isTrue()
        assertThat(pkceChallenge(result.verifier)).isEqualTo(parameters["code_challenge"])
        assertThat(result.toString().contains(result.code)).isFalse()
        assertThat(result.toString().contains(result.verifier)).isFalse()
        assertThat(testSubject.toString().contains(parameters.getValue("state"))).isFalse()
        assertThat(testSubject.accept(callback(testSubject))).isNull()
    }

    @Test
    fun `unrelated and malformed callbacks cannot consume a valid attempt`() {
        val testSubject = OAuthAuthorizationAttempt.create(configuration)
        val good = callback(testSubject)
        val attacks = listOf(
            good.replace("127.0.0.1", "localhost"),
            good.replace(":49152", ":49153"),
            good.replace("http:", "https:"),
            good.replace("/callback?", "/other?"),
            good.replace("127.0.0.1", "attacker@127.0.0.1"),
            "$good#fragment",
            "$good&state=duplicate",
            "$good&%73tate=duplicate",
            "$good&code=duplicate",
            "$good&error=access_denied",
            good.replace("synthetic-code", ""),
            good.replace("synthetic-code", "%00secret"),
            good.replace("synthetic-code", "%FF"),
            good.replace("synthetic-code", "%ZZ"),
            good.replace(parameters(testSubject).getValue("state"), "wrong"),
            "$good&extra=" + "x".repeat(8192),
            "$good&" + (1..33).joinToString("&") { "extra$it=value" },
        )
        attacks.forEach { assertThat(testSubject.accept(it)).isNull() }
        assertThat(testSubject.accept(good) is OAuthCallback.Authorized).isTrue()
    }

    @Test
    fun `denial consumes state without returning provider error text`() {
        val testSubject = OAuthAuthorizationAttempt.create(configuration)
        val response = callback(
            testSubject,
        ).replace("code=synthetic-code", "error=access_denied&error_description=private")
        assertThat(testSubject.accept(response)).isEqualTo(OAuthCallback.Denied)
        assertThat(testSubject.accept(callback(testSubject))).isNull()
    }

    @Test
    fun `cancel and exact expiry prevent later acceptance`() {
        var now = 1L
        val testSubject = OAuthAuthorizationAttempt.create(configuration) { now }
        now += 300_000_000_000L
        assertThat(testSubject.accept(callback(testSubject))).isNull()
        val cancelled = OAuthAuthorizationAttempt.create(configuration)
        cancelled.cancel()
        assertThat(cancelled.accept(callback(cancelled))).isNull()
    }

    @Test
    fun `independent attempts do not share state or verifier and concurrent replay has one winner`() {
        val testSubject = OAuthAuthorizationAttempt.create(configuration)
        val other = OAuthAuthorizationAttempt.create(configuration)
        assertThat(parameters(testSubject)["state"] == parameters(other)["state"]).isFalse()
        assertThat(parameters(testSubject)["code_challenge"] == parameters(other)["code_challenge"]).isFalse()
        assertThat(other.accept(callback(testSubject))).isNull()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { testSubject.accept(callback(testSubject)) } })
            assertThat(results.count { it.get() is OAuthCallback.Authorized }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
        assertThat(other.accept(callback(other)) is OAuthCallback.Authorized).isTrue()
    }

    @Test
    fun `unsafe endpoint and callback configurations are rejected`() {
        val invalid = listOf(
            configuration.copy(authorizationEndpoint = "http://login.example.invalid/authorize"),
            configuration.copy(authorizationEndpoint = "https://user@login.example.invalid/authorize"),
            configuration.copy(authorizationEndpoint = "https://login.example.invalid/authorize?state=override"),
            configuration.copy(redirectUri = "http://0.0.0.0:49152/callback"),
            configuration.copy(redirectUri = "http://127.0.0.1/callback"),
            configuration.copy(redirectUri = "http://127.0.0.1:49152/callback#fragment"),
            configuration.copy(scopes = listOf("mail read")),
        )
        invalid.forEach {
            assertThat(runCatching { OAuthAuthorizationAttempt.create(it) }.isFailure).isTrue()
        }
    }

    private fun callback(attempt: OAuthAuthorizationAttempt): String =
        "${configuration.redirectUri}?state=${parameters(attempt).getValue("state")}&code=synthetic-code"

    private fun parameters(attempt: OAuthAuthorizationAttempt): Map<String, String> =
        attempt.authorizationUri.rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
        }
}
