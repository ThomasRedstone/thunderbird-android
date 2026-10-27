package net.thunderbird.app.ubuntutouch

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import net.thunderbird.core.common.oauth.OAuthConfiguration

internal enum class OAuthTokenFailure {
    REAUTH_REQUIRED,
    CONFIGURATION,
    UNAVAILABLE,
    INVALID_RESPONSE,
    INSUFFICIENT_SCOPE,
}
internal class OAuthTokenException(val reason: OAuthTokenFailure) : IOException("OAuth token request: ${reason.name}")

/** Public-client code/PKCE and refresh grants. No registration, embedded client secret or identity-token parsing. */
internal class OAuthTokenClient(
    private val http: OAuthTokenHttp,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun exchange(configuration: OAuthConfiguration, authorization: OAuthCallback.Authorized): OAuthTokens {
        require(authorization.redirectUri == configuration.redirectUri) { "OAuth redirect mismatch" }
        require(VERIFIER.matches(authorization.verifier)) { "Invalid OAuth verifier" }
        require(
            authorization.code.isNotBlank() && authorization.code.length <= MAX_AUTHORIZATION_CODE &&
                authorization.code.all { it in ' '..'~' },
        ) {
            "Invalid OAuth code"
        }
        return request(
            configuration,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to configuration.clientId,
                "code" to authorization.code,
                "code_verifier" to authorization.verifier,
                "redirect_uri" to authorization.redirectUri,
            ),
            null,
            configuration.scopes.toSet(),
            true,
        )
    }

    suspend fun refresh(configuration: OAuthConfiguration, previous: OAuthTokens): OAuthTokens {
        val refresh = previous.refreshToken ?: throw OAuthTokenException(OAuthTokenFailure.REAUTH_REQUIRED)
        return request(
            configuration,
            mapOf("grant_type" to "refresh_token", "client_id" to configuration.clientId, "refresh_token" to refresh),
            refresh,
            previous.grantScopes,
            false,
        ).withIdentity(previous.identity)
    }

    private suspend fun request(
        configuration: OAuthConfiguration,
        parameters: Map<String, String>,
        previousRefresh: String?,
        grantScopes: Set<String>?,
        initialGrant: Boolean,
    ): OAuthTokens {
        val endpoint = URI(configuration.tokenEndpoint)
        requireTokenEndpoint(endpoint)
        require(configuration.clientId.isNotBlank() && configuration.clientId.length <= MAX_CLIENT_ID) {
            "Invalid OAuth client"
        }
        val started = nowMillis()
        require(started >= 0) { "Invalid OAuth clock" }
        val reply = try {
            http.post(endpoint, parameters)
        } catch (failure: OAuthTokenException) {
            throw failure
        } catch (_: IOException) {
            throw OAuthTokenException(OAuthTokenFailure.UNAVAILABLE)
        }
        return try {
            decodeTokenReply(reply, started, previousRefresh, grantScopes, initialGrant)
        } finally {
            reply.body.fill(0)
        }
    }
}

private fun decodeTokenReply(
    reply: OAuthHttpReply,
    started: Long,
    previousRefresh: String?,
    grantScopes: Set<String>?,
    initialGrant: Boolean,
): OAuthTokens {
    if (reply.status == RATE_LIMITED ||
        reply.status in SERVER_ERRORS
    ) {
        throw OAuthTokenException(OAuthTokenFailure.UNAVAILABLE)
    }
    if (reply.status !in setOf(OK, BAD_REQUEST)) invalidTokenResponse()
    if (reply.body.size > MAX_TOKEN_RESPONSE ||
        !reply.contentType.substringBefore(';').trim().equals("application/json", true)
    ) {
        invalidTokenResponse()
    }
    try {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(reply.body)).toString()
        val wire = tokenJson.decodeFromString(TokenReplySerializer, text)
        rejectTokenError(reply.status, wire.error)
        require(wire.tokenType.equals("Bearer", ignoreCase = true))
        val seconds = requireNotNull(wire.expiresIn)
        require(seconds in 1..MAX_EXPIRY_SECONDS)
        val expires = Math.addExact(started, Math.multiplyExact(seconds, MILLIS_PER_SECOND))
        val granted = wire.scope?.let(::parseOAuthScopes) ?: grantScopes
        return OAuthTokens(
            requireNotNull(wire.access),
            wire.refresh ?: previousRefresh,
            expires,
            granted,
            if (initialGrant) granted else grantScopes,
        )
    } catch (_: SerializationException) {
        invalidTokenResponse()
    } catch (_: IllegalArgumentException) {
        invalidTokenResponse()
    } catch (_: ArithmeticException) {
        invalidTokenResponse()
    } catch (_: java.nio.charset.CharacterCodingException) {
        invalidTokenResponse()
    }
}

private fun rejectTokenError(status: Int, error: String?) {
    if (status != OK || error != null) {
        val reason = when {
            status != BAD_REQUEST -> OAuthTokenFailure.INVALID_RESPONSE
            error == "invalid_grant" -> OAuthTokenFailure.REAUTH_REQUIRED
            error in setOf("invalid_client", "unauthorized_client", "invalid_scope") -> OAuthTokenFailure.CONFIGURATION
            else -> OAuthTokenFailure.INVALID_RESPONSE
        }
        throw OAuthTokenException(reason)
    }
}

private class TokenReply {
    var access: String? = null
    var refresh: String? = null
    var tokenType: String? = null
    var expiresIn: Long? = null
    var error: String? = null
    var scope: String? = null
}

/** Decode known fields explicitly so duplicate (including escaped) keys cannot silently overwrite tokens. */
private object TokenReplySerializer : KSerializer<TokenReply> {
    override val descriptor = buildClassSerialDescriptor("OAuthTokenReply") {
        element<String>("access_token", isOptional = true)
        element<String>("refresh_token", isOptional = true)
        element<String>("token_type", isOptional = true)
        element<Long>("expires_in", isOptional = true)
        element<String>("error", isOptional = true)
        element<String>("scope", isOptional = true)
    }
    override fun serialize(encoder: Encoder, value: TokenReply): Unit = throw SerializationException("Decode only")

    override fun deserialize(decoder: Decoder): TokenReply = decoder.decodeStructure(descriptor) {
        val result = TokenReply()
        val seen = BooleanArray(descriptor.elementsCount)
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (seen[index]) throw SerializationException("Duplicate token response field")
            seen[index] = true
            when (index) {
                ACCESS -> result.access = decodeStringElement(descriptor, index)
                REFRESH -> result.refresh = decodeStringElement(descriptor, index)
                TYPE -> result.tokenType = decodeStringElement(descriptor, index)
                EXPIRY -> result.expiresIn = decodeLongElement(descriptor, index)
                ERROR -> result.error = decodeStringElement(descriptor, index)
                SCOPE -> result.scope = decodeStringElement(descriptor, index)
            }
        }
        result
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val tokenJson = Json {
    ignoreUnknownKeys = true
    exceptionsWithDebugInfo = false
}
private val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")
private const val MAX_AUTHORIZATION_CODE = 4096
private const val MAX_EXPIRY_SECONDS = 604_800L
private const val MILLIS_PER_SECOND = 1000L
private const val OK = 200
private const val BAD_REQUEST = 400
private const val RATE_LIMITED = 429
private const val FIRST_SERVER_ERROR = 500
private const val LAST_SERVER_ERROR = 599
private val SERVER_ERRORS = FIRST_SERVER_ERROR..LAST_SERVER_ERROR
private const val ACCESS = 0
private const val REFRESH = 1
private const val TYPE = 2
private const val EXPIRY = 3
private const val ERROR = 4
private const val SCOPE = 5

private fun invalidTokenResponse(): Nothing = throw OAuthTokenException(OAuthTokenFailure.INVALID_RESPONSE)

private const val MAX_CLIENT_ID = 4096
