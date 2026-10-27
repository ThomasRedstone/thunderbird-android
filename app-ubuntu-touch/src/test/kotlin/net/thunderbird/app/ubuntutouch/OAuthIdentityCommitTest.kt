package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.ConnectionSecurity
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OAuthIdentityCommitTest {
    @get:Rule val directory = TemporaryFolder()
    private val policy = OAuthIdentityPolicy("https://login.example.invalid/profile")
    private val configuration = OAuthConfiguration(
        "synthetic",
        listOf("mail"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )
    private val providers = OAuthProviderRegistry(
        listOf(
            OAuthProviderSpec(
                "controlled",
                configuration,
                OAuthMailServer("imap.example.invalid", 993, ConnectionSecurity.SSL_TLS_REQUIRED),
                OAuthMailServer("smtp.example.invalid", 587, ConnectionSecurity.STARTTLS_REQUIRED),
                requiredScopes = setOf("mail"),
                identityPolicy = policy,
            ),
        ),
    )
    private val raw = FakeCredentialStore()
    private val tokens = OAuthTokens("access", "refresh", 500000, setOf("mail"))

    @Test
    fun `identity proof is mandatory before commit and ordinary edits cannot change authenticated address`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), raw, providers)
            val account = providers.candidate("controlled", "Controlled", "fixture@example.invalid", null)
            assertThat(runCatching { testSubject.createWithOAuth(account, tokens) }.isFailure).isTrue()
            assertThat(raw.read(account.credentialKey)).isNull()
            assertThat(database.query("SELECT key FROM credential_cleanup") { getText(0) }).isEqualTo(emptyList())
            val proof = OAuthVerifiedIdentity.fromProfile(policy, "unique-protected-subject", account.address, tokens)
            testSubject.createWithOAuth(account, tokens, proof)
            val stored = OAuthTokenStore(ManagedOAuthCredentialStore(testSubject))
                .read(OAuthGrant(account.id, account.credentialKey), configuration)!!
            stored.requireIdentity(providers.identityRequirementFor(account))
            assertThat(
                runCatching {
                    testSubject.updateSync(account.id) {
                        it.copy(
                            address = "other@example.invalid",
                            incoming = it.incoming.copy(username = "other@example.invalid"),
                            outgoing = it.outgoing.copy(username = "other@example.invalid"),
                        )
                    }
                }.isFailure,
            ).isTrue()
            assertThat(testSubject.updateSync(account.id) { it.copy(displayName = "Renamed") }!!.address)
                .isEqualTo(account.address)
        }
        assertThat("unique-protected-subject" in directory.root.resolve("mail.sqlite").readText()).isEqualTo(false)
    }

    @Test
    fun `stolen token proof or changed subject cannot replace an established grant`() {
        MailDatabase(directory.root.toPath().resolve("replace.sqlite")).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), raw, providers)
            val account = providers.candidate("controlled", "Controlled", "fixture@example.invalid", null)
            val proof = OAuthVerifiedIdentity.fromProfile(policy, "original-subject", account.address, tokens)
            testSubject.createWithOAuth(account, tokens, proof)
            val replacement = providers.candidate("controlled", "Controlled", account.address, account)
            val changed = OAuthTokens("replacement", "new-refresh", 500000, setOf("mail"))
            assertThat(
                runCatching {
                    testSubject.replaceWithOAuth(account, replacement, changed, proof)
                }.isFailure,
            ).isTrue()
            val wrong = OAuthVerifiedIdentity.fromProfile(policy, "different-subject", account.address, changed)
            assertThat(
                runCatching {
                    testSubject.replaceWithOAuth(account, replacement, changed, wrong)
                }.isFailure,
            ).isTrue()
            assertThat(testSubject.findById(account.id)).isEqualTo(account)
            assertThat(raw.read(replacement.credentialKey)).isNull()
            val accepted = OAuthVerifiedIdentity.fromProfile(policy, "original-subject", account.address, changed)
            testSubject.replaceWithOAuth(account, replacement, changed, accepted)
            assertThat(testSubject.findById(account.id)).isEqualTo(replacement)
        }
    }

    @Test
    fun `protected identity survives refresh and unknown identity cannot authenticate`() = runBlocking {
        val store = OAuthTokenStore(raw)
        val account = providers.candidate("controlled", "Controlled", "fixture@example.invalid", null)
        val grant = OAuthGrant(account.id, account.credentialKey)
        val requirement = providers.identityRequirementFor(account)
        val testSubject = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    OAuthHttpReply(
                        200,
                        "application/json",
                        """{"access_token":"rotated","refresh_token":"new-refresh","token_type":"Bearer",
                "expires_in":3600}""".toByteArray(),
                    )
                },
            ) { 0 },
        ) { 0 }
        store.write(grant, configuration, tokens)
        assertThat(
            runCatching { testSubject.token(grant, configuration, setOf("mail"), requirement) }
                .exceptionOrNull() is OAuthIdentityException,
        ).isTrue()
        val proof = OAuthVerifiedIdentity.fromProfile(policy, "subject", account.address, tokens)
        store.write(grant, configuration, tokens.withIdentity(proof.binding))
        testSubject.invalidate(grant, configuration, "access")
        assertThat(testSubject.token(grant, configuration, setOf("mail"), requirement)).isEqualTo("rotated")
        val reopened = OAuthTokenStore(raw).read(grant, configuration)!!
        reopened.requireIdentity(requirement)
        assertThat(reopened.identity!!.subject).isEqualTo("subject")
        assertThat(
            runCatching {
                reopened.requireIdentity(requirement!!.copy(address = "other@example.invalid"))
            }.isFailure,
        ).isTrue()
    }
}
