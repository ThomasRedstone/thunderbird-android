package net.thunderbird.app.ubuntutouch

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import net.thunderbird.core.common.oauth.OAuthConfiguration

/** In-memory, single-use authorization attempt. The owner must cancel it when leaving account setup. */
internal class OAuthAuthorizationAttempt private constructor(
    val authorizationUri: URI,
    private val redirect: URI,
    private var verifier: String?,
    private var state: String?,
    private val started: Long,
    private val now: () -> Long,
) {
    /** Invalid/unrelated callbacks leave the attempt available; a matching success or denial consumes it. */
    @Suppress("ReturnCount") // Explicit rejection guards keep untrusted callback checks auditable.
    @Synchronized
    fun accept(callback: String): OAuthCallback? {
        if (now() - started >= LIFETIME_NANOS) cancel()
        val expected = state ?: return null
        val parameters = callbackParameters(callback, redirect) ?: return null
        val received = parameters["state"] ?: return null
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), received.toByteArray(Charsets.UTF_8))) {
            return null
        }
        val code = parameters["code"]
        val error = parameters["error"]
        if ((code == null) == (error == null)) return null
        val result = if (code != null) {
            if (code.isBlank() || code.length > MAX_CODE || code.any { it.code !in 32..126 }) return null
            OAuthCallback.Authorized(code, checkNotNull(verifier), redirect.toASCIIString())
        } else {
            if (error.isNullOrBlank()) return null
            OAuthCallback.Denied
        }
        cancel()
        return result
    }

    @Synchronized
    fun cancel() {
        verifier = null
        state = null
    }

    override fun toString(): String = "OAuthAuthorizationAttempt(redacted)"

    companion object {
        fun create(
            configuration: OAuthConfiguration,
            now: () -> Long = System::nanoTime,
        ): OAuthAuthorizationAttempt {
            val endpoint = URI(configuration.authorizationEndpoint)
            require(
                endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
                    endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null,
            ) { "Invalid authorization endpoint" }
            val redirect = URI(configuration.redirectUri)
            require(
                redirect.scheme == "http" && redirect.host == "127.0.0.1" && redirect.port in 1..MAX_PORT &&
                    redirect.rawUserInfo == null && redirect.rawQuery == null && redirect.rawFragment == null &&
                    redirect.rawPath.startsWith('/'),
            ) { "An allocated IPv4 loopback callback is required" }
            require(configuration.clientId.isNotBlank() && configuration.scopes.isNotEmpty())
            require(
                configuration.scopes.all { scope ->
                    scope.isNotEmpty() &&
                        scope.all { it.code in VISIBLE_ASCII && it != '"' && it != '\\' }
                },
            )
            val random = SecureRandom()
            val verifier = random.token()
            val state = random.token()
            val query = linkedMapOf(
                "response_type" to "code",
                "client_id" to configuration.clientId,
                "redirect_uri" to redirect.toASCIIString(),
                "scope" to configuration.scopes.joinToString(" "),
                "state" to state,
                "code_challenge" to pkceChallenge(verifier),
                "code_challenge_method" to "S256",
            ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
            return OAuthAuthorizationAttempt(
                URI("${endpoint.toASCIIString()}?$query"),
                redirect,
                verifier,
                state,
                now(),
                now,
            )
        }
    }
}

internal sealed interface OAuthCallback {
    class Authorized(val code: String, val verifier: String, val redirectUri: String) : OAuthCallback {
        override fun toString(): String = "OAuthCallback.Authorized(redacted)"
    }
    data object Denied : OAuthCallback
}

internal fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

private fun SecureRandom.token(): String = ByteArray(ENTROPY_BYTES).also(::nextBytes).let {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it)
}

@Suppress("ReturnCount") // Reject malformed or ambiguous input at the first failing boundary.
private fun parseParameters(query: String): Map<String, String>? {
    val result = mutableMapOf<String, String>()
    for (part in query.split('&')) {
        val separator = part.indexOf('=')
        if (separator < 1 || result.size >= MAX_PARAMETERS) return null
        val pair = try {
            URLDecoder.decode(part.substring(0, separator), Charsets.UTF_8) to
                URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (result.put(pair.first, pair.second) != null) return null
        if (pair.first.any { it.code !in VISIBLE_ASCII } || pair.second.any { it.isISOControl() }) return null
    }
    return result
}

private const val LIFETIME_NANOS = 300_000_000_000L
private const val MAX_CALLBACK = 8192
private const val MAX_CODE = 4096
private const val MAX_PARAMETERS = 32

@Suppress("ReturnCount") // Exact redirect matching must precede decoding any callback parameters.
private fun callbackParameters(callback: String, redirect: URI): Map<String, String>? {
    if (callback.length > MAX_CALLBACK) return null
    val uri = try {
        URI(callback)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme != redirect.scheme || uri.rawAuthority != redirect.rawAuthority) return null
    if (uri.rawPath != redirect.rawPath || uri.rawFragment != null) return null
    return parseParameters(uri.rawQuery ?: return null)
}

private const val ENTROPY_BYTES = 32
private const val MAX_PORT = 65535
private const val FIRST_VISIBLE_ASCII = 33
private const val LAST_VISIBLE_ASCII = 126
private val VISIBLE_ASCII = FIRST_VISIBLE_ASCII..LAST_VISIBLE_ASCII
