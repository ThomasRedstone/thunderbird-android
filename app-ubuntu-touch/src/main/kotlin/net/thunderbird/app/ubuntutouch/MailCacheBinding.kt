package net.thunderbird.app.ubuntutouch

import java.nio.ByteBuffer
import java.security.MessageDigest

/** A fingerprint of the immutable account context that supplied cached bytes; never inferred from a later account. */
internal fun mailCacheBinding(account: UtMailAccount): String {
    val server = account.incoming
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(
        account.id.toString(), account.credentialKey, account.address, account.oauthProviderId.orEmpty(),
        server.type, server.host, server.port.toString(), server.connectionSecurity.name,
        server.authenticationType.name, server.username,
    ).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal const val CACHE_BINDING_KEY = "mailCacheBinding"
