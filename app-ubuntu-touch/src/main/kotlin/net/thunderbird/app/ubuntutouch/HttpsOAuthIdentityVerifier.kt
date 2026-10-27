package net.thunderbird.app.ubuntutouch

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun interface OAuthIdentityHttp {
    suspend fun get(endpoint: URI, accessToken: String): OAuthHttpReply
}

internal fun interface OAuthIdentityVerifier {
    suspend fun verify(policy: OAuthIdentityPolicy, tokens: OAuthTokens): OAuthVerifiedIdentity
}

/** OAuth resource-profile verification only; never interprets an access token or an unvalidated ID token as JWT. */
internal class HttpsOAuthIdentityVerifier(private val http: OAuthIdentityHttp) : OAuthIdentityVerifier {
    override suspend fun verify(policy: OAuthIdentityPolicy, tokens: OAuthTokens): OAuthVerifiedIdentity {
        val reply = try {
            http.get(URI(policy.endpoint), tokens.accessToken)
        } catch (_: IOException) {
            throw OAuthIdentityException()
        }
        return verifiedProfile(reply, policy, tokens)
    }
}

private fun verifiedProfile(
    reply: OAuthHttpReply,
    policy: OAuthIdentityPolicy,
    tokens: OAuthTokens,
): OAuthVerifiedIdentity {
    return try {
        require(reply.status == OK && reply.body.size <= MAX_TOKEN_RESPONSE)
        require(reply.contentType.substringBefore(';').trim().equals("application/json", true))
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(reply.body)).toString()
        val profile = identityJson.decodeFromString(ProfileSerializer, text)
        require(profile.verified == true)
        OAuthVerifiedIdentity.fromProfile(
            policy,
            requireNotNull(profile.subject),
            requireNotNull(profile.email),
            tokens,
        )
    } catch (_: IllegalArgumentException) {
        throw OAuthIdentityException()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw OAuthIdentityException()
    } finally {
        reply.body.fill(0)
    }
}

private class Profile {
    var subject: String? = null
    var email: String? = null
    var verified: Boolean? = null
}

private object ProfileSerializer : KSerializer<Profile> {
    override val descriptor = buildClassSerialDescriptor("OAuthVerifiedEmailProfile") {
        element<String>("sub", isOptional = true)
        element<String>("email", isOptional = true)
        element<Boolean>("email_verified", isOptional = true)
    }
    override fun serialize(encoder: Encoder, value: Profile): Unit = throw SerializationException("Decode only")
    override fun deserialize(decoder: Decoder): Profile = decoder.decodeStructure(descriptor) {
        val result = Profile()
        val seen = BooleanArray(descriptor.elementsCount)
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (seen[index]) throw SerializationException("Duplicate identity field")
            seen[index] = true
            when (index) {
                SUBJECT -> result.subject = decodeStringElement(descriptor, index)
                EMAIL -> result.email = decodeStringElement(descriptor, index)
                VERIFIED -> result.verified = decodeSerializableElement(descriptor, index, StrictVerifiedBoolean)
            }
        }
        result
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val identityJson = Json {
    ignoreUnknownKeys = true
    exceptionsWithDebugInfo = false
}
private const val SUBJECT = 0
private const val EMAIL = 1
private const val VERIFIED = 2
private const val OK = 200

/** Json's Boolean decoder accepts quoted values; identity verification requires an actual JSON boolean. */
private object StrictVerifiedBoolean : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("VerifiedBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean): Unit = throw SerializationException("Decode only")
    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString) throw SerializationException("Invalid verification flag")
        return value.booleanOrNull ?: throw SerializationException("Invalid verification flag")
    }
}
