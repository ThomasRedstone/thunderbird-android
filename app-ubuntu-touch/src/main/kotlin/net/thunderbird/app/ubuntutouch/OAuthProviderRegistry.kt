package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.net.URI
import net.thunderbird.core.common.net.HostNameUtils
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory

internal data class OAuthMailServer(val host: String, val port: Int, val security: ConnectionSecurity) {
    init {
        require(HostNameUtils.isLegalHostNameOrIP(host) != null)
        require(port in 1..MAX_MAIL_PORT && security != ConnectionSecurity.NONE)
    }

    fun matches(settings: ServerSettings): Boolean =
        host.equals(settings.host, ignoreCase = true) && port == settings.port &&
            security == settings.connectionSecurity
}

/** Registration and mail-server allowlist supplied by composition, never inferred from editable account fields. */
internal data class OAuthProviderSpec(
    val id: String,
    val configuration: OAuthConfiguration,
    val incoming: OAuthMailServer,
    val outgoing: OAuthMailServer,
    val displayName: String = id,
    val requiredScopes: Set<String> = emptySet(),
    val identityPolicy: OAuthIdentityPolicy? = null,
) {
    init {
        require(
            displayName.isNotBlank() && displayName.length <= MAX_PROVIDER_NAME &&
                displayName.none { it.isISOControl() },
        )
        require(requiredScopes.isEmpty() || validOAuthScopes(requiredScopes))
        require(configuration.scopes.containsAll(requiredScopes))
        require(PROVIDER_ID.matches(id)) { "Invalid OAuth provider ID" }
        require(configuration.clientId.isNotBlank() && configuration.clientId.length <= MAX_CLIENT_ID)
        require(configuration.scopes.size in 1..MAX_SCOPES && configuration.scopes.all(::validScope))
        requireTokenEndpoint(URI(configuration.authorizationEndpoint))
        requireTokenEndpoint(URI(configuration.tokenEndpoint))
    }
}

internal class OAuthProviderRegistry(specs: List<OAuthProviderSpec> = emptyList()) {
    private val providers = specs.associate { spec ->
        spec.id to spec.copy(
            configuration = spec.configuration.copy(scopes = spec.configuration.scopes.toList()),
            requiredScopes = spec.requiredScopes.toSet(),
        )
    }

    init {
        require(providers.size == specs.size) { "Duplicate OAuth provider ID" }
    }

    fun choices(): List<OAuthProviderChoice> = providers.values.map { OAuthProviderChoice(it.id, it.displayName) }

    fun candidate(providerId: String, name: String, address: String, existing: UtMailAccount?): UtMailAccount {
        val provider = providers[providerId] ?: unavailableProvider()
        val grant = OAuthGrant(existing?.id ?: AccountIdFactory.create())
        return UtMailAccount(
            grant.accountId,
            name,
            address,
            provider.incoming.settings("imap", address),
            provider.outgoing.settings("smtp", address),
            grant.credentialKey,
            provider.id,
        )
    }

    fun identityRequirementFor(account: UtMailAccount): OAuthIdentityRequirement? {
        configurationFor(account)
        return providers.getValue(checkNotNull(account.oauthProviderId)).identityPolicy?.let {
            OAuthIdentityRequirement(it, account.address)
        }
    }

    fun requiredScopesFor(account: UtMailAccount): Set<String> {
        configurationFor(account)
        return providers.getValue(checkNotNull(account.oauthProviderId)).requiredScopes.toSet()
    }

    fun configurationFor(account: UtMailAccount): OAuthConfiguration {
        val provider = providers[account.oauthProviderId] ?: unavailableProvider()
        if (!account.isOAuth || !provider.incoming.matches(account.incoming) ||
            !provider.outgoing.matches(account.outgoing)
        ) {
            unavailableProvider()
        }
        if (provider.identityPolicy != null &&
            (account.incoming.username != account.address || account.outgoing.username != account.address)
        ) {
            unavailableProvider()
        }
        return provider.configuration.copy(scopes = provider.configuration.scopes.toList())
    }
}

internal fun validOAuthProviderId(value: String): Boolean = PROVIDER_ID.matches(value)
private fun validScope(scope: String): Boolean = scope.isNotEmpty() && scope.length <= MAX_SCOPE_LENGTH &&
    scope.all { it in '!'..'~' && it != '"' && it != '\\' }
private fun unavailableProvider(): Nothing = throw AuthenticationFailedException(
    "OAuth provider or mail server unavailable",
)
private val PROVIDER_ID = Regex("[a-z][a-z0-9._-]{0,63}")
private const val MAX_MAIL_PORT = 65535
private const val MAX_CLIENT_ID = 4096
private const val MAX_SCOPES = 64
private const val MAX_SCOPE_LENGTH = 512

internal data class OAuthProviderChoice(val id: String, val name: String)

private fun OAuthMailServer.settings(type: String, username: String) = ServerSettings(
    type,
    host,
    port,
    security,
    AuthType.XOAUTH2,
    username,
    null,
    null,
)

private const val MAX_PROVIDER_NAME = 256
