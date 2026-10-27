package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OAuthAccountAuthorizationTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var database: MailDatabase
    private lateinit var accounts: UtAccountManager
    private val raw = FakeCredentialStore()
    private val calls = AtomicInteger()
    private val configuration = OAuthConfiguration(
        "synthetic-client",
        listOf("mail"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/unused",
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

    @Before
    fun open() {
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        accounts = UtAccountManager(AccountSettingsStorage(database), raw, providers)
    }

    @After
    fun close() = database.close()

    @Test
    fun `browser exchange verifies pinned profile before committing an identity bound grant`() = runBlocking {
        val strict = identityProviders()
        accounts = UtAccountManager(AccountSettingsStorage(database), raw, strict)
        val candidate = candidate()
        val verifier = HttpsOAuthIdentityVerifier(
            OAuthIdentityHttp { _, access ->
                assertThat(access).isEqualTo("protected-access")
                identityReply()
            },
        )
        val testSubject = identityService(strict, verifier).prepare(candidate)
        testSubject.authorize()
        val stored = OAuthTokenStore(ManagedOAuthCredentialStore(accounts))
            .read(OAuthGrant(candidate.id, candidate.credentialKey), configuration)!!
        stored.requireIdentity(strict.identityRequirementFor(candidate))
        assertThat(stored.identity!!.subject).isEqualTo("profile-subject")
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        accounts = UtAccountManager(AccountSettingsStorage(database), raw, strict)
        val reopened = OAuthTokenStore(ManagedOAuthCredentialStore(accounts))
            .read(OAuthGrant(candidate.id, candidate.credentialKey), configuration)!!
        reopened.requireIdentity(strict.identityRequirementFor(candidate))
    }

    @Test
    fun `profile for another email cannot create the configured account`() = runBlocking {
        val strict = identityProviders()
        accounts = UtAccountManager(AccountSettingsStorage(database), raw, strict)
        val verifier = HttpsOAuthIdentityVerifier(
            OAuthIdentityHttp { _, _ ->
                identityReply("other@example.invalid")
            },
        )
        val testSubject = identityService(strict, verifier).prepare(candidate())
        assertThat(runCatching { testSubject.authorize() }.exceptionOrNull() is OAuthIdentityException).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
        assertThat(raw.values.isEmpty()).isTrue()
    }

    @Test
    fun `cancelling blocked profile verification prevents the protected commit`() = runBlocking {
        val strict = identityProviders()
        accounts = UtAccountManager(AccountSettingsStorage(database), raw, strict)
        val entered = CompletableDeferred<Unit>()
        val verifier = HttpsOAuthIdentityVerifier(
            OAuthIdentityHttp { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val testSubject = identityService(strict, verifier).prepare(candidate())
        val pending = async { runCatching { testSubject.authorize() } }
        withTimeout(5000) { entered.await() }
        assertThat(testSubject.cancel()).isTrue()
        assertThat(withTimeout(5000) { pending.await() }.isFailure).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
        assertThat(raw.values.isEmpty()).isTrue()
    }

    @Test
    fun `browser PKCE callback exchanges exact allocated redirect and commits protected account`() = runBlocking {
        val candidate = candidate()
        var dispatched: URI? = null
        val testSubject = service(
            OAuthBrowser {
                dispatched = it
                respond(it)
            },
        ) { _, parameters ->
            val query = query(checkNotNull(dispatched))
            assertThat(parameters["redirect_uri"]).isEqualTo(query["redirect_uri"])
            assertThat(parameters["code"]).isEqualTo("synthetic-code")
            assertThat(pkceChallenge(parameters.getValue("code_verifier"))).isEqualTo(query["code_challenge"])
            reply()
        }.prepare(candidate)
        assertThat(testSubject.authorize()).isEqualTo(OAuthAccountAuthorizationResult(candidate.id, false))
        assertThat(accounts.findById(candidate.id)).isEqualTo(candidate)
        val store = OAuthTokenStore(ManagedOAuthCredentialStore(accounts))
        assertThat(store.read(OAuthGrant(candidate.id, candidate.credentialKey), configuration)!!.refreshToken)
            .isEqualTo("protected-refresh")
        assertThat(calls.get()).isEqualTo(1)
        assertThat(testSubject.cancel()).isFalse()
        assertThat(runCatching { testSubject.authorize() }.isFailure).isTrue()
        assertClosed(checkNotNull(dispatched))
        val databaseText = String(directory.root.resolve("mail.sqlite").readBytes(), Charsets.ISO_8859_1)
        assertThat("protected-refresh" in databaseText || "synthetic-code" in databaseText).isFalse()
    }

    @Test
    fun `denial closes receiver without token exchange or account commit`() = runBlocking {
        var uri: URI? = null
        val testSubject = service(
            OAuthBrowser {
                uri = it
                respond(it, denied = true)
            },
        ).prepare(candidate())
        assertThat(
            runCatching {
                testSubject.authorize()
            }.exceptionOrNull() is OAuthAuthorizationDeniedException,
        ).isTrue()
        assertThat(calls.get()).isEqualTo(0)
        assertThat(raw.values.isEmpty()).isTrue()
        assertClosed(checkNotNull(uri))
    }

    @Test
    fun `browser dispatch failure closes allocated receiver and preserves accounts`() = runBlocking {
        var uri: URI? = null
        val testSubject = service(
            OAuthBrowser {
                uri = it
                throw OAuthBrowserException()
            },
        ).prepare(candidate())
        assertThat(runCatching { testSubject.authorize() }.exceptionOrNull() is OAuthBrowserException).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
        assertThat(calls.get()).isEqualTo(0)
        assertClosed(checkNotNull(uri))
    }

    @Test
    fun `timeout closes receiver and does not contact token endpoint`() = runBlocking {
        var uri: URI? = null
        val testSubject = service(OAuthBrowser { uri = it }, timeoutMillis = 100).prepare(candidate())
        assertThat(runCatching { testSubject.authorize() }.exceptionOrNull() is OAuthLoopbackException).isTrue()
        assertThat(calls.get()).isEqualTo(0)
        assertThat(accounts.findAll().isEmpty()).isTrue()
        assertClosed(checkNotNull(uri))
    }

    @Test
    fun `cancel while waiting closes receiver and rejects late callback`() = runBlocking {
        val dispatched = CompletableDeferred<URI>()
        val testSubject = service(OAuthBrowser { dispatched.complete(it) }).prepare(candidate())
        val pending = async { runCatching { testSubject.authorize() } }
        val uri = withTimeout(5000) { dispatched.await() }
        assertThat(testSubject.cancel()).isTrue()
        assertThat(withTimeout(5000) { pending.await() }.isFailure).isTrue()
        assertClosed(uri)
        assertThat(calls.get()).isEqualTo(0)
        assertThat(accounts.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `cancel closes callback even while browser dispatcher is blocked`() = runBlocking {
        val dispatched = CompletableDeferred<URI>()
        val release = CountDownLatch(1)
        val testSubject = service(
            OAuthBrowser {
                dispatched.complete(it)
                check(release.await(5, TimeUnit.SECONDS))
            },
        ).prepare(candidate())
        val pending = async { runCatching { testSubject.authorize() } }
        try {
            val uri = withTimeout(5000) { dispatched.await() }
            assertThat(testSubject.cancel()).isTrue()
            assertClosed(uri)
        } finally {
            release.countDown()
        }
        assertThat(withTimeout(5000) { pending.await() }.isFailure).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `cancel pending code exchange prevents account commit`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val testSubject = service(OAuthBrowser { respond(it) }) { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }.prepare(candidate())
        val pending = async { runCatching { testSubject.authorize() } }
        withTimeout(5000) { entered.await() }
        assertThat(testSubject.cancel()).isTrue()
        assertThat(withTimeout(5000) { pending.await() }.isFailure).isTrue()
        assertThat(raw.values.isEmpty()).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `account changed during browser sign in rejects late grant without writing credentials`() = runBlocking {
        val old = candidate()
        accounts.createWithOAuth(old, OAuthTokens("old-access", "old-refresh", 500000))
        val dispatched = CompletableDeferred<URI>()
        val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
        val testSubject = service(OAuthBrowser { dispatched.complete(it) }).prepare(replacement, old)
        val pending = async { runCatching { testSubject.authorize() } }
        val uri = withTimeout(5000) { dispatched.await() }
        accounts.updateSync(old.id) { it.copy(displayName = "Changed") }
        respond(uri)
        assertThat(withTimeout(5000) { pending.await() }.isFailure).isTrue()
        assertThat(raw.values.keys).isEqualTo(setOf(old.credentialKey))
        assertThat(accounts.findById(old.id)!!.displayName).isEqualTo("Changed")
    }

    @Test
    fun `failed account SQL commit retains journaled grant for cleanup without claiming success`() = runBlocking {
        database.execute(
            "CREATE TRIGGER fail_insert BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'fixture'); END",
        )
        val candidate = candidate()
        val testSubject = service(OAuthBrowser { respond(it) }).prepare(candidate)
        assertThat(runCatching { testSubject.authorize() }.isFailure).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
        assertThat(raw.values.keys).isEqualTo(setOf(candidate.credentialKey))
        assertThat(accounts.cleanupUnusedCredentials()).isTrue()
        assertThat(raw.values.isEmpty()).isTrue()
    }

    @Test
    fun `unapproved mail host fails before browser opens`() {
        val candidate = candidate().let { it.copy(incoming = it.incoming.copy(host = "other.example.invalid")) }
        val testSubject = service(OAuthBrowser { error("Must not open browser") })
        assertThat(runCatching { testSubject.prepare(candidate) }.isFailure).isTrue()
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun `reauthorization commits a new generation and removes old protected credentials`() = runBlocking {
        val old = candidate()
        accounts.createWithOAuth(old, OAuthTokens("old-access", "old-refresh", 500000))
        val replacement = old.copy(credentialKey = OAuthGrant(old.id).credentialKey)
        val testSubject = service(OAuthBrowser { respond(it) }).prepare(replacement, old)
        assertThat(testSubject.authorize()).isEqualTo(OAuthAccountAuthorizationResult(old.id, false))
        assertThat(accounts.findById(old.id)).isEqualTo(replacement)
        assertThat(raw.values.keys).isEqualTo(setOf(replacement.credentialKey))
    }

    @Test
    fun `token exchange failure never commits a new account`() = runBlocking {
        var uri: URI? = null
        val testSubject = service(
            OAuthBrowser {
                uri = it
                respond(it)
            },
        ) { _, _ ->
            throw java.io.IOException("Controlled failure")
        }.prepare(candidate())
        assertThat(runCatching { testSubject.authorize() }.isFailure).isTrue()
        assertThat(accounts.findAll().isEmpty()).isTrue()
        assertThat(raw.values.isEmpty()).isTrue()
        assertClosed(checkNotNull(uri))
    }

    @Test
    fun `cancel before authorization prevents browser allocation`() = runBlocking {
        val testSubject = service(OAuthBrowser { error("Cancelled attempt must not dispatch") }).prepare(candidate())
        assertThat(testSubject.cancel()).isTrue()
        assertThat(runCatching { testSubject.authorize() }.isFailure).isTrue()
        assertThat(calls.get()).isEqualTo(0)
        assertThat(accounts.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `cancellation reports too late once protected commit starts and waits for its outcome`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val gated = object : CredentialStore by raw {
            override fun write(key: String, value: ByteArray) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                raw.write(key, value)
            }
        }
        accounts = UtAccountManager(AccountSettingsStorage(database), gated, providers)
        val candidate = candidate()
        val testSubject = service(OAuthBrowser { respond(it) }).prepare(candidate)
        val pending = async { testSubject.authorize() }
        try {
            withTimeout(5000) { entered.await() }
            assertThat(testSubject.committing).isTrue()
            assertThat(testSubject.cancel()).isFalse()
            assertThat(pending.isCompleted).isFalse()
        } finally {
            release.countDown()
        }
        assertThat(withTimeout(5000) { pending.await() })
            .isEqualTo(OAuthAccountAuthorizationResult(candidate.id, false))
        assertThat(accounts.findById(candidate.id)).isEqualTo(candidate)
    }

    private fun identityProviders() = OAuthProviderRegistry(
        listOf(
            OAuthProviderSpec(
                "controlled",
                configuration,
                OAuthMailServer("imap.example.invalid", 993, ConnectionSecurity.SSL_TLS_REQUIRED),
                OAuthMailServer("smtp.example.invalid", 587, ConnectionSecurity.STARTTLS_REQUIRED),
                requiredScopes = setOf("mail"),
                identityPolicy = OAuthIdentityPolicy("https://login.example.invalid/profile"),
            ),
        ),
    )

    private fun identityService(
        registry: OAuthProviderRegistry,
        verifier: OAuthIdentityVerifier,
    ) = OAuthAccountAuthorization(
        accounts,
        registry,
        OAuthBrowser { respond(it) },
        OAuthTokenClient(OAuthTokenHttp { _, _ -> reply() }) { 0 },
        { OAuthLoopbackReceiver.open(it, 2000, 50) },
        identity = verifier,
    )

    private fun identityReply(email: String = "fixture@example.invalid") = OAuthHttpReply(
        200,
        "application/json",
        """{"sub":"profile-subject","email":"$email","email_verified":true}""".toByteArray(),
    )

    private fun service(
        browser: OAuthBrowser,
        timeoutMillis: Int = 2000,
        post: suspend (URI, Map<String, String>) -> OAuthHttpReply = { _, _ -> reply() },
    ) = OAuthAccountAuthorization(
        accounts,
        providers,
        browser,
        OAuthTokenClient(
            OAuthTokenHttp { endpoint, parameters ->
                calls.incrementAndGet()
                post(endpoint, parameters)
            },
        ) { 0 },
        { OAuthLoopbackReceiver.open(it, timeoutMillis, 50) },
    )

    private fun candidate(): UtMailAccount {
        val grant = OAuthGrant(AccountIdFactory.create())
        return UtMailAccount(
            grant.accountId,
            "Controlled",
            "fixture@example.invalid",
            server("imap", 993, ConnectionSecurity.SSL_TLS_REQUIRED),
            server("smtp", 587, ConnectionSecurity.STARTTLS_REQUIRED),
            grant.credentialKey,
            "controlled",
        )
    }

    private fun server(type: String, port: Int, security: ConnectionSecurity) = ServerSettings(
        type,
        "$type.example.invalid",
        port,
        security,
        AuthType.XOAUTH2,
        "fixture@example.invalid",
        null,
        null,
    )

    private fun reply() = OAuthHttpReply(
        200,
        "application/json",
        """{"access_token":"protected-access","refresh_token":"protected-refresh",
            "token_type":"Bearer","expires_in":3600}""".toByteArray(),
    )

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
    }

    private fun respond(authorization: URI, denied: Boolean = false) {
        val parameters = query(authorization)
        val redirect = URI(parameters.getValue("redirect_uri"))
        val result = if (denied) "error=access_denied" else "code=synthetic-code"
        Socket(redirect.host, redirect.port).use { socket ->
            socket.getOutputStream().write(
                (
                    "GET ${redirect.path}?state=${parameters.getValue("state")}&$result HTTP/1.1\r\n" +
                        "Host: ${redirect.authority}\r\n\r\n"
                    )
                    .toByteArray(),
            )
        }
    }

    private fun assertClosed(authorization: URI) {
        val redirect = URI(query(authorization).getValue("redirect_uri"))
        assertThat(
            runCatching {
                Socket().use { it.connect(InetSocketAddress(redirect.host, redirect.port), 100) }
            }.isFailure,
        ).isTrue()
    }
}
