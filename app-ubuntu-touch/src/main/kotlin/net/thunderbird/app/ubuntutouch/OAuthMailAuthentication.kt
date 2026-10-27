package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.OAuth2TokenProvider

/** App composition supplies a shared coordinator backed by the manager's live-ownership credential fence. */
internal class OAuthMailAuthentication(
    private val providers: OAuthProviderRegistry,
    private val coordinator: OAuthTokenCoordinator,
) {
    fun credentials(account: UtMailAccount, resources: MailSessionResources): MailSessionAuthentication {
        val configuration = providers.configurationFor(account)
        val required = providers.requiredScopesFor(account)
        val grant = OAuthGrant(account.id, account.credentialKey)
        val incoming = resources.own(
            CoordinatedOAuth2TokenProvider(grant, configuration, coordinator, requiredScopes = required),
        )
        val outgoing = resources.own(
            CoordinatedOAuth2TokenProvider(grant, configuration, coordinator, requiredScopes = required),
        )
        return MailSessionAuthentication(account.incoming, account.outgoing, incoming, outgoing)
    }
}

internal data class MailSessionAuthentication(
    val incoming: ServerSettings,
    val outgoing: ServerSettings,
    val incomingProvider: OAuth2TokenProvider? = null,
    val outgoingProvider: OAuth2TokenProvider? = null,
)

/** Cancellation may race construction. A resource registered after close is immediately closed and rejected. */
internal class MailSessionResources(sockets: CancellableSocketFactory) : AutoCloseable {
    private val monitor = Any()
    private var closed = false

    @Volatile var isRetired = false
        private set
    private val owners = mutableListOf<AutoCloseable>(sockets)

    fun <T : AutoCloseable> own(resource: T): T {
        synchronized(monitor) {
            if (!closed && !isRetired) {
                owners += resource
                return resource
            }
        }
        resource.close()
        throw MailOperationCancelledException()
    }

    fun retire() {
        synchronized(monitor) { isRetired = true }
    }

    fun ensureActive() {
        if (isRetired) throw MailOperationCancelledException()
    }

    override fun close() {
        val pending = synchronized(monitor) {
            if (closed) return
            closed = true
            isRetired = true
            owners.asReversed().toList().also { owners.clear() }
        }
        closeMailResources(pending)
    }
}
