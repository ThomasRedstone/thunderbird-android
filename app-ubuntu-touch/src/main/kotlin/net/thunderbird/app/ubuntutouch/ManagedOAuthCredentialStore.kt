package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException

internal class StaleOAuthGrantException : CancellationException("OAuth credential generation changed")

/** Every protected call shares the account owner's replacement/deletion monitor. */
internal class ManagedOAuthCredentialStore(private val accounts: UtAccountManager) : CredentialStore {
    override fun read(
        key: String,
    ): ByteArray? = access(key, accounts::readOAuthCredential)
    override fun write(
        key: String,
        value: ByteArray,
    ) = access(key) { grant -> accounts.writeOAuthCredential(grant, value) }
    override fun delete(key: String) = access(key, accounts::deleteOAuthCredential)

    private fun <T> access(key: String, operation: (OAuthGrant) -> T): T {
        val grant = try {
            OAuthGrant.fromKey(key)
        } catch (_: IllegalArgumentException) {
            throw StaleOAuthGrantException()
        }
        return operation(grant)
    }
}
