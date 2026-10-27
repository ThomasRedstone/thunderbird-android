package net.thunderbird.app.ubuntutouch

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class OAuthIdentityPolicy(val endpoint: String) {
    init {
        require(endpoint.length <= MAX_IDENTITY_ENDPOINT)
        requireTokenEndpoint(URI(endpoint))
    }
}

internal data class OAuthIdentityRequirement(val policy: OAuthIdentityPolicy, val address: String) {
    override fun toString(): String = "OAuthIdentityRequirement(redacted)"
}

internal class OAuthIdentityException : IOException("OAuth identity verification required or failed")

internal class OAuthIdentityBinding(endpointDigest: ByteArray, val subject: String, val email: String) {
    private val endpointDigest = endpointDigest.copyOf()
    init {
        require(endpointDigest.size == IDENTITY_DIGEST_BYTES)
        require(subject.length in 1..MAX_SUBJECT && subject.all { it in '!'..'~' })
        require(email.isNotBlank() && email.none { it.isWhitespace() || it.isISOControl() })
        require(
            Charsets.UTF_8.newEncoder().canEncode(email) && email.toByteArray(Charsets.UTF_8).size <= MAX_EMAIL_BYTES,
        )
    }

    fun matches(requirement: OAuthIdentityRequirement): Boolean =
        email == requirement.address &&
            MessageDigest.isEqual(endpointDigest, identityDigest(requirement.policy.endpoint))

    fun sameSubject(other: OAuthIdentityBinding): Boolean =
        MessageDigest.isEqual(endpointDigest, other.endpointDigest) && subject == other.subject

    fun write(output: DataOutputStream) {
        output.write(endpointDigest)
        output.writeUTF(subject)
        output.writeUTF(email)
    }

    override fun toString(): String = "OAuthIdentityBinding(redacted)"
}

/**
 * A transient proof is bound to the exact credential pair whose access token was used at the pinned profile endpoint.
 */
internal class OAuthVerifiedIdentity private constructor(
    val binding: OAuthIdentityBinding,
    private val tokenDigest: ByteArray,
) {
    fun check(requirement: OAuthIdentityRequirement, tokens: OAuthTokens) {
        if (!binding.matches(requirement) || !MessageDigest.isEqual(tokenDigest, identityCredentialDigest(tokens))) {
            throw OAuthIdentityException()
        }
    }
    override fun toString(): String = "OAuthVerifiedIdentity(redacted)"
    companion object {
        fun fromProfile(policy: OAuthIdentityPolicy, subject: String, email: String, tokens: OAuthTokens) =
            OAuthVerifiedIdentity(
                OAuthIdentityBinding(identityDigest(policy.endpoint), subject, email),
                identityCredentialDigest(tokens),
            )
    }
}

internal fun identityDigest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

internal fun OAuthTokens.requireIdentity(requirement: OAuthIdentityRequirement?) {
    if (requirement != null && identity?.matches(requirement) != true) throw OAuthIdentityException()
}

internal fun OAuthTokens.withIdentity(binding: OAuthIdentityBinding?): OAuthTokens =
    OAuthTokens(accessToken, refreshToken, expiresAtMillis, scopes, grantScopes, binding)

internal fun DataOutputStream.writeOAuthIdentity(identity: OAuthIdentityBinding?) {
    writeBoolean(identity != null)
    identity?.write(this)
}

internal fun DataInputStream.readOAuthIdentity(): OAuthIdentityBinding? {
    val marker = readUnsignedByte()
    require(marker in 0..1)
    if (marker == 0) return null
    val endpoint = ByteArray(IDENTITY_DIGEST_BYTES)
    readFully(endpoint)
    return OAuthIdentityBinding(endpoint, readUTF(), readUTF())
}

private const val IDENTITY_DIGEST_BYTES = 32
private const val MAX_SUBJECT = 255
private const val MAX_EMAIL_BYTES = 320

private const val MAX_IDENTITY_ENDPOINT = 2048

private fun identityCredentialDigest(tokens: OAuthTokens): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    for (value in listOf(tokens.accessToken, tokens.refreshToken)) {
        if (value == null) {
            digest.update(0)
        } else {
            digest.update(1)
            val bytes = value.toByteArray(Charsets.UTF_8)
            try {
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }
    return digest.digest()
}
