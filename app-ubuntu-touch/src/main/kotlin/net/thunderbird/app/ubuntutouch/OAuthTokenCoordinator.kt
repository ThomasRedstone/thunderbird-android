package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.thunderbird.core.common.oauth.OAuthConfiguration

/** Share one coordinator across mail operations. No background scope or persistent plaintext cache is created. */
internal class OAuthTokenCoordinator(
    private val store: OAuthTokenStore,
    private val client: OAuthTokenClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val registryLock = Any()
    private val entries = mutableMapOf<OAuthGrant, Entry>()

    suspend fun token(
        grant: OAuthGrant,
        configuration: OAuthConfiguration,
        requiredScopes: Set<String> = emptySet(),
        identity: OAuthIdentityRequirement? = null,
    ): String = withGrant(grant) { entry ->
        val snapshot = configuration.snapshot()
        val required = requiredScopes.toSet()
        persistPending(grant, snapshot, entry)
        val current = store.read(grant, snapshot) ?: throw OAuthTokenException(OAuthTokenFailure.REAUTH_REQUIRED)
        val now = nowMillis()
        require(now >= 0) { "Invalid OAuth clock" }
        if (current.expiresAtMillis - now > EXPIRY_MARGIN_MILLIS) {
            current.requireScopes(required)
            current.requireIdentity(identity)
            current.accessToken
        } else {
            val replacement = try {
                client.refresh(snapshot, current)
            } catch (failure: OAuthTokenException) {
                if (failure.reason == OAuthTokenFailure.REAUTH_REQUIRED) store.delete(grant)
                throw failure
            }
            entry.pending = Pending(snapshot, replacement)
            persistPending(grant, snapshot, entry)
            if (replacement.expiresAtMillis <= nowMillis()) throw OAuthTokenException(OAuthTokenFailure.UNAVAILABLE)
            replacement.requireScopes(required)
            replacement.requireIdentity(identity)
            replacement.accessToken
        }
    }

    /** The caller must already have validated its grant-setup generation and authorization result. */
    suspend fun storeGrant(
        grant: OAuthGrant,
        configuration: OAuthConfiguration,
        tokens: OAuthTokens,
    ) = withGrant(grant) { entry ->
        val snapshot = configuration.snapshot()
        entry.pending = Pending(snapshot, tokens)
        persistPending(grant, snapshot, entry)
    }

    /** Reject only the actual issued token; a delayed failure must not invalidate a newer concurrent refresh. */
    suspend fun invalidate(
        grant: OAuthGrant,
        configuration: OAuthConfiguration,
        rejectedAccessToken: String,
    ) = withGrant(grant) { entry ->
        val snapshot = configuration.snapshot()
        persistPending(grant, snapshot, entry)
        val current = store.read(grant, snapshot)
        if (current?.accessToken == rejectedAccessToken) {
            entry.pending =
                Pending(
                    snapshot,
                    OAuthTokens(
                        current.accessToken,
                        current.refreshToken,
                        0,
                        current.scopes,
                        current.grantScopes,
                        current.identity,
                    ),
                )
            persistPending(grant, snapshot, entry)
        }
    }

    /** Account removal must call this as part of its credential cleanup; no remote revocation is implied. */
    suspend fun delete(grant: OAuthGrant) = withGrant(grant) { entry ->
        withContext(NonCancellable) {
            store.delete(grant)
            entry.pending = null
        }
    }

    private suspend fun persistPending(grant: OAuthGrant, configuration: OAuthConfiguration, entry: Entry) {
        val pending = entry.pending ?: return
        if (!pending.matches(configuration)) throw OAuthTokenStorageException()
        // Once a rotated credential is observed, finish its protected write despite caller cancellation.
        // On service failure retain it for a write retry, never return it or refresh the old credential again.
        withContext(NonCancellable) {
            store.write(grant, pending.configuration, pending.tokens)
            entry.pending = null
        }
    }

    private suspend fun <T> withGrant(grant: OAuthGrant, operation: suspend (Entry) -> T): T = withContext(
        Dispatchers.IO,
    ) {
        val entry = synchronized(registryLock) {
            entries.getOrPut(grant, ::Entry).also { it.users++ }
        }
        try {
            entry.mutex.withLock {
                try {
                    operation(entry)
                } catch (failure: StaleOAuthGrantException) {
                    entry.pending = null
                    throw failure
                }
            }
        } finally {
            synchronized(registryLock) {
                entry.users--
                // Waiters retain references before locking, preventing two live mutexes for the same grant.
                if (entry.users == 0 && entry.pending == null) entries.remove(grant)
            }
        }
    }

    override fun toString(): String = "OAuthTokenCoordinator(redacted)"

    private class Entry {
        val mutex = Mutex()
        var users = 0
        var pending: Pending? = null
    }

    private class Pending(val configuration: OAuthConfiguration, val tokens: OAuthTokens) {
        fun matches(other: OAuthConfiguration): Boolean =
            configuration.clientId == other.clientId &&
                configuration.authorizationEndpoint == other.authorizationEndpoint &&
                configuration.tokenEndpoint == other.tokenEndpoint &&
                configuration.scopes.toSet() == other.scopes.toSet()
    }
}

private fun OAuthConfiguration.snapshot(): OAuthConfiguration = copy(scopes = scopes.toList())
private const val EXPIRY_MARGIN_MILLIS = 60_000L
