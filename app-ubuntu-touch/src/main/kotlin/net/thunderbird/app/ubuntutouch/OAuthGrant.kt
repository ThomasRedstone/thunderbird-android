package net.thunderbird.app.ubuntutouch

import java.util.UUID
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory

/** Immutable protected-credential generation, separate from the account's stable identity. */
internal data class OAuthGrant(
    val accountId: AccountId,
    val credentialKey: String = "$KEY_PREFIX$accountId:${UUID.randomUUID()}",
) {
    init {
        val prefix = "$KEY_PREFIX$accountId:"
        require(credentialKey.length == prefix.length + GENERATION_LENGTH && credentialKey.startsWith(prefix)) {
            "Invalid OAuth credential reference"
        }
        val generation = credentialKey.removePrefix(prefix)
        val valid = try {
            UUID.fromString(generation).toString() == generation
        } catch (_: IllegalArgumentException) {
            false
        }
        require(valid) { "Invalid OAuth credential generation" }
    }

    companion object {
        fun fromKey(key: String): OAuthGrant {
            require(key.startsWith(KEY_PREFIX) && key.length == KEY_PREFIX.length + GENERATION_LENGTH * 2 + 1) {
                "Invalid OAuth credential reference"
            }
            val account = AccountIdFactory.of(key.substring(KEY_PREFIX.length, KEY_PREFIX.length + GENERATION_LENGTH))
            return OAuthGrant(account, key)
        }
    }

    override fun toString(): String = "OAuthGrant(redacted)"
}

private const val KEY_PREFIX = "ut-oauth-v2:"
private const val GENERATION_LENGTH = 36
