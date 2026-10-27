package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.net.URI
import net.thunderbird.core.common.net.HostNameUtils
import net.thunderbird.core.common.oauth.OAuthConfiguration

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
) {
    init {
        require(PROVIDER_ID.matches(id)) { "Invalid OAuth provider ID" }
        require(configuration.clientId.isNotBlank() && configuration.clientId.length <= MAX_CLIENT_ID)
        require(configuration.scopes.size in 1..MAX_SCOPES && configuration.scopes.all(::validScope))
        requireTokenEndpoint(URI(configuration.authorizationEndpoint))
        requireTokenEndpoint(URI(configuration.tokenEndpoint))
    }
}

internal class OAuthProviderRegistry(specs: List<OAuthProviderSpec> = emptyList()) {
    private val providers = specs.associate { spec ->
        spec.id to spec.copy(configuration = spec.configuration.copy(scopes = spec.configuration.scopes.toList()))
    }

    init {
        require(providers.size == specs.size) { "Duplicate OAuth provider ID" }
    }

    fun configurationFor(account: UtMailAccount): OAuthConfiguration {
        val provider = providers[account.oauthProviderId] ?: unavailableProvider()
        if (!account.isOAuth || !provider.incoming.matches(account.incoming) ||
            !provider.outgoing.matches(account.outgoing)
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
