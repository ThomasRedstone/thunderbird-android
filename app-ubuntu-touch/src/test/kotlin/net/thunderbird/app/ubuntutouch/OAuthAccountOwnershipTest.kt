package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OAuthAccountOwnershipTest {
    @get:Rule val directory = TemporaryFolder()
    private val path get() = directory.root.toPath().resolve("mail.sqlite")
    private val configuration = OAuthConfiguration(
        "synthetic-client",
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
            ),
        ),
    )
    private val credentials = Credentials()
    private val tokens = OAuthTokens("private-access-value", "private-refresh-value", 500000)

    @Test
    fun `required scope gate rejects creation and replacement before changing account or protected credentials`() {
        val strict = OAuthProviderRegistry(
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
        MailDatabase(path).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), credentials, strict)
            val account = account()
            assertThat(runCatching { testSubject.createWithOAuth(account, tokens) }.isFailure).isTrue()
            assertThat(testSubject.findAll()).isEqualTo(emptyList())
            assertThat(credentials.read(account.credentialKey)).isNull()
            val accepted = OAuthTokens("access", "refresh", 500000, setOf("mail"))
            testSubject.createWithOAuth(account, accepted)
            val replacement = account.copy(credentialKey = OAuthGrant(account.id).credentialKey)
            assertThat(
                runCatching {
                    testSubject.replaceWithOAuth(account, replacement, tokens)
                }.isFailure,
            ).isTrue()
            assertThat(testSubject.findById(account.id)).isEqualTo(account)
            assertThat(credentials.read(replacement.credentialKey)).isNull()
            assertThat(
                OAuthTokenStore(ManagedOAuthCredentialStore(testSubject))
                    .read(grant(account), configuration)!!.refreshToken,
            ).isEqualTo("refresh")
            assertThat(database.query("SELECT key FROM credential_cleanup") { getText(0) }).isEqualTo(emptyList())
        }
    }

    @Test
    fun `OAuth metadata reopens while tokens remain only in protected storage`() {
        val account = account()
        MailDatabase(path).use { database -> manager(database).createWithOAuth(account, tokens) }
        MailDatabase(path).use { database ->
            val owner = manager(database)
            val testSubject = OAuthTokenStore(ManagedOAuthCredentialStore(owner))
            assertThat(owner.findAll()).isEqualTo(listOf(account))
            assertThat(testSubject.read(grant(account), configuration)!!.refreshToken).isEqualTo(tokens.refreshToken)
            credentials.reads.set(0)
            assertThat(runCatching { owner.passwords(account.id) }.isFailure).isTrue()
            assertThat(credentials.reads.get()).isEqualTo(0)
        }
        val text = String(path.toFile().readBytes(), Charsets.ISO_8859_1)
        assertThat(tokens.accessToken in text || tokens.refreshToken!! in text).isFalse()
    }

    @Test
    fun `late refresh after removal cannot recreate protected credentials`() = runBlocking {
        MailDatabase(path).use { database ->
            val owner = manager(database)
            val account = account()
            owner.createWithOAuth(account, OAuthTokens("expired", "refresh", 0))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val testSubject = OAuthTokenCoordinator(
                OAuthTokenStore(ManagedOAuthCredentialStore(owner)),
                client {
                    entered.complete(Unit)
                    release.await()
                },
            ) { 0 }
            val pending = async { runCatching { testSubject.token(grant(account), configuration) } }
            withTimeout(5000) { entered.await() }
            owner.delete(account.id)
            release.complete(Unit)
            assertThat(withTimeout(5000) { pending.await() }.exceptionOrNull() is StaleOAuthGrantException).isTrue()
            assertThat(credentials.values.isEmpty()).isTrue()
            assertThat(owner.findAll().isEmpty()).isTrue()
            assertThat(database.query("SELECT key FROM credential_cleanup") { getText(0) }.isEmpty()).isTrue()
        }
    }

    @Test
    fun `late refresh after replacement cannot recreate retired key or overwrite new authorization`() = runBlocking {
        MailDatabase(path).use { database ->
            val owner = manager(database)
            val old = account()
            owner.createWithOAuth(old, OAuthTokens("expired", "refresh", 0))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = OAuthTokenStore(ManagedOAuthCredentialStore(owner))
            val testSubject = OAuthTokenCoordinator(
                store,
                client {
                    entered.complete(Unit)
                    release.await()
                },
            ) { 0 }
            val pending = async { runCatching { testSubject.token(grant(old), configuration) } }
            withTimeout(5000) { entered.await() }
            val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
            assertThat(owner.replaceWithOAuth(old, replacement, tokens)).isTrue()
            release.complete(Unit)
            assertThat(withTimeout(5000) { pending.await() }.exceptionOrNull() is StaleOAuthGrantException).isTrue()
            assertThat(credentials.values.keys).isEqualTo(setOf(replacement.credentialKey))
            assertThat(store.read(grant(replacement), configuration)!!.refreshToken).isEqualTo(tokens.refreshToken)
        }
    }

    @Test
    fun `account replacement cancels an owned engine provider waiting for OAuth refresh`() = runBlocking {
        MailDatabase(path).use { database ->
            val owner = manager(database)
            val old = account()
            owner.createWithOAuth(old, OAuthTokens("expired", "refresh", 0))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = OAuthTokenCoordinator(
                OAuthTokenStore(ManagedOAuthCredentialStore(owner)),
                client {
                    entered.complete(Unit)
                    release.await()
                },
            ) { 0 }
            val resources = MailSessionResources(CancellableSocketFactory(JvmTrustedSocketFactory()))
            owner.registerSession(owner.findById(old.id)!!, resources)
            resources.own(AutoCloseable { owner.releaseSession(resources) })
            val provider = resources.own(CoordinatedOAuth2TokenProvider(grant(old), configuration, coordinator))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val pending = worker.submit<Boolean> { runCatching { provider.getToken(30000) }.isFailure }
                withTimeout(5000) { entered.await() }
                val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
                owner.replaceWithOAuth(old, replacement, tokens)
                assertThat(pending.get(5, TimeUnit.SECONDS)).isTrue()
                assertThat(resources.isRetired).isTrue()
                assertThat(credentials.values.keys).isEqualTo(setOf(replacement.credentialKey))
                assertThat(
                    OAuthTokenStore(ManagedOAuthCredentialStore(owner))
                        .read(grant(replacement), configuration)!!.refreshToken,
                ).isEqualTo(tokens.refreshToken)
            } finally {
                release.complete(Unit)
                resources.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun `failed insert leaves journaled grant that is cleaned after reopening`() {
        val account = account()
        MailDatabase(path).use { database ->
            database.execute(
                "CREATE TRIGGER fail_insert BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'fixture'); END",
            )
            val testSubject = manager(database)
            assertThat(runCatching { testSubject.createWithOAuth(account, tokens) }.isFailure).isTrue()
            assertThat(testSubject.findAll().isEmpty()).isTrue()
            assertThat(credentials.values.keys).isEqualTo(setOf(account.credentialKey))
        }
        MailDatabase(path).use { database ->
            val testSubject = manager(database)
            assertThat(testSubject.cleanupUnusedCredentials()).isTrue()
            assertThat(credentials.values.isEmpty()).isTrue()
            val store = OAuthTokenStore(ManagedOAuthCredentialStore(testSubject))
            assertThat(
                runCatching {
                    store.write(grant(account), configuration, tokens)
                }.exceptionOrNull() is StaleOAuthGrantException,
            ).isTrue()
        }
    }

    @Test
    fun `failed replacement commit preserves current grant and cleans only unclaimed replacement`() {
        MailDatabase(path).use { database ->
            val testSubject = manager(database)
            val old = account()
            testSubject.createWithOAuth(old, tokens)
            val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
            database.execute(
                "CREATE TRIGGER fail_update BEFORE UPDATE ON accounts BEGIN SELECT RAISE(ABORT,'fixture'); END",
            )
            assertThat(
                runCatching {
                    testSubject.replaceWithOAuth(old, replacement, OAuthTokens("new", "new-refresh", 0))
                }.isFailure,
            ).isTrue()
            assertThat(testSubject.findById(old.id)).isEqualTo(old)
            assertThat(testSubject.cleanupUnusedCredentials()).isTrue()
            assertThat(credentials.values.keys).isEqualTo(setOf(old.credentialKey))
            assertThat(
                OAuthTokenStore(ManagedOAuthCredentialStore(testSubject)).read(grant(old), configuration)!!.accessToken,
            )
                .isEqualTo(tokens.accessToken)
        }
    }

    @Test
    fun `failed retired-key cleanup is retryable and cannot authorize stale writes`() {
        MailDatabase(path).use { database ->
            val testSubject = manager(database)
            val old = account()
            testSubject.createWithOAuth(old, tokens)
            val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
            credentials.failDeletes.add(old.credentialKey)
            assertThat(testSubject.replaceWithOAuth(old, replacement, tokens)).isFalse()
            assertThat(testSubject.findById(old.id)).isEqualTo(replacement)
            val store = OAuthTokenStore(ManagedOAuthCredentialStore(testSubject))
            assertThat(
                runCatching {
                    store.write(grant(old), configuration, tokens)
                }.exceptionOrNull() is StaleOAuthGrantException,
            ).isTrue()
            credentials.failDeletes.clear()
            assertThat(testSubject.cleanupUnusedCredentials()).isTrue()
            assertThat(credentials.values.keys).isEqualTo(setOf(replacement.credentialKey))
        }
    }

    @Test
    fun `unapproved hosts provider changes and password paths fail before secret access`() {
        MailDatabase(path).use { database ->
            val testSubject = manager(database)
            val account = account()
            val evil = account.copy(incoming = account.incoming.copy(host = "attacker.example.invalid"))
            assertThat(runCatching { testSubject.createWithOAuth(evil, tokens) }.isFailure).isTrue()
            assertThat(credentials.writes.get()).isEqualTo(0)
            testSubject.createWithOAuth(account, tokens)
            credentials.reads.set(0)
            credentials.writes.set(0)
            assertThat(runCatching { testSubject.updateSync(account.id) { evil } }.isFailure).isTrue()
            assertThat(
                runCatching {
                    testSubject.updateSync(account.id) { it.copy(oauthProviderId = "other") }
                }.isFailure,
            ).isTrue()
            assertThat(
                runCatching {
                    testSubject.replacePasswords(account.id, AccountPasswords("incoming", "outgoing"))
                }.isFailure,
            ).isTrue()
            assertThat(credentials.reads.get() + credentials.writes.get()).isEqualTo(0)
            assertThat(testSubject.findById(account.id)).isEqualTo(account)
        }
    }

    @Test
    fun `stale setup snapshot and locked removal leave current metadata and credentials intact`() {
        MailDatabase(path).use { database ->
            val testSubject = manager(database)
            val old = account()
            testSubject.createWithOAuth(old, tokens)
            testSubject.updateSync(old.id) { it.copy(displayName = "Renamed") }
            val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
            credentials.writes.set(0)
            assertThat(runCatching { testSubject.replaceWithOAuth(old, replacement, tokens) }.isFailure).isTrue()
            assertThat(credentials.writes.get()).isEqualTo(0)
            credentials.locked = true
            assertThat(runCatching { testSubject.delete(old.id) }.exceptionOrNull() is SecretStoreException).isTrue()
            assertThat(testSubject.findById(old.id)!!.credentialKey).isEqualTo(old.credentialKey)
            credentials.locked = false
            assertThat(credentials.values.keys).isEqualTo(setOf(old.credentialKey))
        }
    }

    @Test
    fun `schema nine upgrade preserves password account cache and outbox`() {
        val id = AccountIdFactory.create()
        val passwords = AccountPasswords("incoming-password", "outgoing-password")
        val passwordAccount = UtMailAccount(
            id,
            "Password",
            "fixture@example.invalid",
            server("imap", AuthType.PLAIN),
            server("smtp", AuthType.PLAIN),
            UUID.randomUUID().toString(),
        )
        MailDatabase(path).use { database ->
            manager(database).createWithPasswords(passwordAccount, passwords)
            database.execute(
                "INSERT INTO outgoing(id,account,recipients,subject,body,message_id,state) VALUES(?,?,?,?,?,?,?)",
                "draft",
                id.toString(),
                "to@example.invalid",
                "Kept",
                "Kept body",
                "<kept@fixture.invalid>",
                "DRAFT",
            )
            database.execute(
                "INSERT INTO folders(account,server_id,name,type) VALUES(?,?,?,?)",
                id.toString(),
                "INBOX",
                "Inbox",
                "INBOX",
            )
            val folder = database.query("SELECT id FROM folders") { getLong(0) }.single()
            database.execute(
                "INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)",
                folder,
                "1",
                "Subject: Kept\r\n\r\nBody".toByteArray(),
            )
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=9")
        }
        MailDatabase(path).use { database ->
            val testSubject = manager(database)
            assertThat(testSubject.findById(id)).isEqualTo(passwordAccount)
            assertThat(testSubject.passwords(id).incoming).isEqualTo(passwords.incoming)
            assertThat(database.query("SELECT body FROM outgoing") { getText(0) }).isEqualTo(listOf("Kept body"))
            assertThat(database.query("SELECT uid FROM messages") { getText(0) }).isEqualTo(listOf("1"))
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(14L)
        }
    }

    private fun manager(database: MailDatabase) = UtAccountManager(
        AccountSettingsStorage(database),
        credentials,
        providers,
    )
    private fun grant(account: UtMailAccount) = OAuthGrant(account.id, account.credentialKey)
    private fun account(): UtMailAccount {
        val grant = OAuthGrant(AccountIdFactory.create())
        return UtMailAccount(
            grant.accountId,
            "OAuth",
            "fixture@example.invalid",
            server("imap"),
            server("smtp"),
            grant.credentialKey,
            "controlled",
        )
    }
    private fun server(type: String, auth: AuthType = AuthType.XOAUTH2) = ServerSettings(
        type,
        if (type == "imap") "imap.example.invalid" else "smtp.example.invalid",
        if (type == "imap") 993 else 587,
        if (type == "imap") ConnectionSecurity.SSL_TLS_REQUIRED else ConnectionSecurity.STARTTLS_REQUIRED,
        auth,
        "fixture@example.invalid",
        null,
        null,
    )
    private fun client(beforeReply: suspend () -> Unit) = OAuthTokenClient(
        OAuthTokenHttp { _, _ ->
            beforeReply()
            OAuthHttpReply(
                200,
                "application/json",
                """
            {"access_token":"rotated-access","refresh_token":"rotated-refresh",
            "token_type":"Bearer","expires_in":3600}
                """.trimIndent().toByteArray(),
            )
        },
    ) { 0 }

    private class Credentials : CredentialStore {
        val values = ConcurrentHashMap<String, ByteArray>()
        val failDeletes = ConcurrentHashMap.newKeySet<String>()
        val reads = AtomicInteger()
        val writes = AtomicInteger()

        @Volatile var locked = false
        override fun read(
            key: String,
        ): ByteArray? {
            checkAvailable()
            reads.incrementAndGet()
            return values[key]?.copyOf()
        }
        override fun write(key: String, value: ByteArray) {
            checkAvailable()
            writes.incrementAndGet()
            values[key] =
                value.copyOf()
        }
        override fun delete(key: String) {
            checkAvailable()
            if (key in failDeletes) throw SecretStoreException(SecretFailure.UNAVAILABLE)
            values.remove(key)?.fill(0)
        }
        private fun checkAvailable() {
            if (locked) throw SecretStoreException(SecretFailure.LOCKED)
        }
    }
}
