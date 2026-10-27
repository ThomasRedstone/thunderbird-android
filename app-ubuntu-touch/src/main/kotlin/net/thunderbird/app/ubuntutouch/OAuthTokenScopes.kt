package net.thunderbird.app.ubuntutouch

import java.io.DataInputStream
import java.io.DataOutputStream

internal fun validOAuthScopes(scopes: Set<String>): Boolean = scopes.size in 1..MAX_OAUTH_SCOPES &&
    scopes.sumOf { it.length } <= MAX_OAUTH_SCOPE_BYTES && scopes.all {
        it.length in 1..MAX_OAUTH_SCOPE_LENGTH && it.all { char -> char in '!'..'~' && char != '"' && char != '\\' }
    }

internal fun parseOAuthScopes(value: String): Set<String> {
    val entries = value.split(' ')
    require(entries.size <= MAX_OAUTH_SCOPES && validOAuthScopes(entries.toSet())) { "Invalid OAuth scopes" }
    return entries.toSet()
}

internal fun OAuthTokens.requireScopes(required: Set<String>) {
    require(required.isEmpty() || validOAuthScopes(required)) { "Invalid required OAuth scopes" }
    if (required.isNotEmpty() && scopes?.containsAll(required) != true) {
        throw OAuthTokenException(OAuthTokenFailure.INSUFFICIENT_SCOPE)
    }
}

internal fun DataOutputStream.writeOAuthScopes(scopes: Set<String>?) {
    writeInt(scopes?.size ?: UNKNOWN_SCOPES)
    scopes?.sorted()?.forEach(::writeUTF)
}

internal fun DataInputStream.readOAuthScopes(): Set<String>? {
    val count = readInt()
    if (count == UNKNOWN_SCOPES) return null
    require(count in 1..MAX_OAUTH_SCOPES) { "Invalid stored scopes" }
    val entries = List(count) { readUTF() }
    val scopes = entries.toSet()
    require(scopes.size == count && validOAuthScopes(scopes)) { "Invalid stored scopes" }
    return scopes
}

private const val UNKNOWN_SCOPES = -1
private const val MAX_OAUTH_SCOPES = 64
private const val MAX_OAUTH_SCOPE_LENGTH = 512
private const val MAX_OAUTH_SCOPE_BYTES = 3072
