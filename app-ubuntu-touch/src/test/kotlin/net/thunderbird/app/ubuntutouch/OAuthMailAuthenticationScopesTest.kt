package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.ConnectionSecurity
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.junit.Test

class OAuthMailAuthenticationScopesTest {
    @Test
    fun `both protocol providers enforce registered minimum scopes before returning cached access`() {
        val configuration = OAuthConfiguration(
            "synthetic",
            listOf("mail"),
            "https://login.example.invalid/auth",
            "https://login.example.invalid/token",
            "http://127.0.0.1:49152/callback",
        )
        val registry = OAuthProviderRegistry(
            listOf(
                OAuthProviderSpec(
                    "controlled",
                    configuration,
                    OAuthMailServer("imap.example.invalid", 993, ConnectionSecurity.SSL_TLS_REQUIRED),
                    OAuthMailServer("smtp.example.invalid", 587, ConnectionSecurity.STARTTLS_REQUIRED),
                    requiredScopes = setOf("mail"),
                ),
            ),
        )
        val account = registry.candidate("controlled", "Controlled", "fixture@example.invalid", null)
        val grant = OAuthGrant(account.id, account.credentialKey)
        val store = OAuthTokenStore(FakeCredentialStore())
        val coordinator = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    error("Fresh cached grant must not make a token request")
                },
            ),
        ) { 0 }
        val testSubject = OAuthMailAuthentication(registry, coordinator)
        MailSessionResources(CancellableSocketFactory(JvmTrustedSocketFactory())).use { resources ->
            val authentication = testSubject.credentials(account, resources)
            store.write(grant, configuration, OAuthTokens("access", "refresh", 500000))
            val providers = listOf(authentication.incomingProvider!!, authentication.outgoingProvider!!)
            providers.forEach {
                assertThat(
                    runCatching {
                        it.getToken(1000)
                    }.exceptionOrNull() is AuthenticationFailedException,
                ).isTrue()
            }
            store.write(grant, configuration, OAuthTokens("accepted", "refresh", 500000, setOf("mail")))
            providers.forEach { assertThat(it.getToken(1000)).isEqualTo("accepted") }
        }
    }
}
