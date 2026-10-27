package net.thunderbird.app.ubuntutouch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import net.thunderbird.core.common.oauth.OAuthConfiguration

internal class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    scopes: Set<String>? = null,
    grantScopes: Set<String>? = scopes,
    val identity: OAuthIdentityBinding? = null,
) {
    val scopes: Set<String>? = scopes?.let { java.util.Collections.unmodifiableSet(it.toSet()) }
    val grantScopes: Set<String>? = grantScopes?.let { java.util.Collections.unmodifiableSet(it.toSet()) }
    init {
        require(validToken(accessToken) && (refreshToken == null || validToken(refreshToken))) { "Invalid OAuth token" }
        require(scopes == null || validOAuthScopes(scopes)) { "Invalid OAuth scopes" }
        require(grantScopes == null || validOAuthScopes(grantScopes)) { "Invalid OAuth grant scopes" }
        require(expiresAtMillis >= 0) { "Invalid OAuth expiry" }
    }

    override fun toString(): String = "OAuthTokens(redacted)"
}

/** Opaque tokens and scope metadata stay in the protected service, never in the mail database. */
internal class OAuthTokenStore(private val credentials: CredentialStore) {
    fun read(grant: OAuthGrant, configuration: OAuthConfiguration): OAuthTokens? {
        val encoded = credentials.read(grant.credentialKey) ?: return null
        return try {
            decode(encoded, binding(grant, configuration))
        } finally {
            encoded.fill(0)
        }
    }

    fun write(grant: OAuthGrant, configuration: OAuthConfiguration, tokens: OAuthTokens) {
        val encoded = encode(binding(grant, configuration), tokens)
        try {
            credentials.write(grant.credentialKey, encoded)
        } finally {
            encoded.fill(0)
        }
    }

    fun delete(grant: OAuthGrant) = credentials.delete(grant.credentialKey)
}

internal class OAuthTokenStorageException : IOException(
    "Stored OAuth state is invalid or belongs to another configuration",
)

private fun binding(grant: OAuthGrant, configuration: OAuthConfiguration): ByteArray {
    val fields =
        listOf(
            grant.accountId.toString(),
            grant.credentialKey,
            configuration.clientId,
            configuration.authorizationEndpoint,
            configuration.tokenEndpoint,
        )
    require(fields.all { it.isNotBlank() && it.length <= MAX_CONFIGURATION_FIELD }) { "Invalid OAuth configuration" }
    require(
        configuration.scopes.size in 1..MAX_SCOPES &&
            configuration.scopes.all { it.isNotBlank() && it.length <= MAX_SCOPE },
    ) {
        "Invalid OAuth scopes"
    }
    val buffer = ByteArrayOutputStream()
    val output = DataOutputStream(buffer)
    output.writeUTF(grant.accountId.toString())
    output.writeUTF(grant.credentialKey)
    output.writeUTF(configuration.clientId)
    output.writeUTF(configuration.authorizationEndpoint)
    output.writeUTF(configuration.tokenEndpoint)
    val scopes = configuration.scopes.distinct().sorted()
    output.writeInt(scopes.size)
    scopes.forEach(output::writeUTF)
    // The callback's ephemeral port changes on every login; it must not invalidate persisted refresh tokens.
    return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
}

private fun encode(binding: ByteArray, tokens: OAuthTokens): ByteArray = SecretBuffer().use { buffer ->
    val output = DataOutputStream(buffer)
    output.writeInt(TOKEN_FORMAT_VERSION)
    output.write(binding)
    output.writeUTF(tokens.accessToken)
    output.writeBoolean(tokens.refreshToken != null)
    tokens.refreshToken?.let(output::writeUTF)
    output.writeLong(tokens.expiresAtMillis)
    output.writeOAuthScopes(tokens.scopes)
    output.writeOAuthScopes(tokens.grantScopes)
    output.writeOAuthIdentity(tokens.identity)
    output.flush()
    buffer.toByteArray()
}

private fun decode(encoded: ByteArray, expectedBinding: ByteArray): OAuthTokens {
    requireStored(encoded.size <= MAX_ENCODED_BYTES)
    try {
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val version = input.readInt()
        requireStored(version in LEGACY_TOKEN_FORMAT_VERSION..TOKEN_FORMAT_VERSION)
        val actualBinding = ByteArray(BINDING_BYTES)
        input.readFully(actualBinding)
        requireStored(MessageDigest.isEqual(expectedBinding, actualBinding))
        val access = input.readUTF()
        val hasRefresh = input.readUnsignedByte()
        requireStored(hasRefresh in 0..1)
        val refresh = if (hasRefresh == 1) input.readUTF() else null
        val expires = input.readLong()
        val scopes = if (version >= SCOPED_TOKEN_FORMAT_VERSION) input.readOAuthScopes() else null
        val grantScopes = if (version >= SCOPED_TOKEN_FORMAT_VERSION) input.readOAuthScopes() else null
        val identity = if (version == TOKEN_FORMAT_VERSION) input.readOAuthIdentity() else null
        requireStored(input.available() == 0)
        return OAuthTokens(access, refresh, expires, scopes, grantScopes, identity)
    } catch (_: IOException) {
        throw OAuthTokenStorageException()
    } catch (_: IllegalArgumentException) {
        throw OAuthTokenStorageException()
    }
}

private fun requireStored(condition: Boolean) {
    if (!condition) throw OAuthTokenStorageException()
}

private fun validToken(value: String): Boolean =
    value.isNotEmpty() && value.length <= MAX_TOKEN_CHARACTERS && value.all { it.code in TOKEN_ASCII }

private class SecretBuffer : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
    }
}

private const val LEGACY_TOKEN_FORMAT_VERSION = 2
private const val SCOPED_TOKEN_FORMAT_VERSION = 3
private const val TOKEN_FORMAT_VERSION = 4
private const val BINDING_BYTES = 32
private const val MAX_TOKEN_CHARACTERS = 16_384
private const val MAX_ENCODED_BYTES = 40_000
private const val FIRST_TOKEN_ASCII = 33
private const val LAST_TOKEN_ASCII = 126
private val TOKEN_ASCII = FIRST_TOKEN_ASCII..LAST_TOKEN_ASCII

private const val MAX_CONFIGURATION_FIELD = 4096
private const val MAX_SCOPES = 64
private const val MAX_SCOPE = 512
