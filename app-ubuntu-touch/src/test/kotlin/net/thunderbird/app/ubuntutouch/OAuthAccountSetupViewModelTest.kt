package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.ConnectionSecurity
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OAuthAccountSetupViewModelTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var database: MailDatabase
    private lateinit var accounts: UtAccountManager
    private val raw = FakeCredentialStore()
    private val configuration = OAuthConfiguration(
        "synthetic",
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
                "Controlled sign-in",
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
    fun `selecting provider clears passwords and preserves name and address`() = runTest {
        val testSubject = model(backgroundScope)
        testSubject.event(AccountSetupEvent.New)
        change(testSubject, AccountField.NAME, "Named")
        change(testSubject, AccountField.ADDRESS, "fixture@example.invalid")
        change(testSubject, AccountField.IMAP_PASSWORD, "private")
        testSubject.event(AccountSetupEvent.SelectProvider("controlled"))
        assertThat(testSubject.state.value.text(AccountField.IMAP_PASSWORD)).isEqualTo("")
        assertThat(testSubject.state.value.text(AccountField.NAME)).isEqualTo("Named")
        assertThat(testSubject.state.value.providerId).isEqualTo("controlled")
        change(testSubject, AccountField.IMAP_PASSWORD, "hidden-input")
        assertThat(testSubject.state.value.text(AccountField.IMAP_PASSWORD)).isEqualTo("")
        testSubject.event(AccountSetupEvent.SelectProvider(null))
        assertThat(testSubject.state.value.text(AccountField.SMTP_PASSWORD)).isEqualTo("")
        assertThat(testSubject.state.value.providerId).isEqualTo(null)
    }

    @Test
    fun `configured OAuth setup creates account using approved servers rather than editable host fields`() = runTest {
        val testSubject = model(backgroundScope)
        fill(testSubject)
        change(testSubject, AccountField.IMAP_HOST, "unapproved.example.invalid")
        testSubject.event(AccountSetupEvent.Save)
        val state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.editing).isFalse()
        val account = state.accounts.single()
        assertThat(account.isOAuth).isTrue()
        assertThat(account.incoming.host).isEqualTo("imap.example.invalid")
        assertThat(account.outgoing.port).isEqualTo(587)
        assertThat(state.text(AccountField.IMAP_PASSWORD)).isEqualTo("")
        assertThat(runCatching { accounts.passwords(account.id) }.isFailure).isTrue()
    }

    @Test
    fun `denial retains OAuth form for retry and never publishes provider details`() = runTest {
        var denied = true
        val testSubject = model(backgroundScope, OAuthBrowser { respond(it, denied) })
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        var state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.editing).isTrue()
        assertThat(state.message.orEmpty().contains("declined")).isTrue()
        assertThat(state.accounts.isEmpty()).isTrue()
        denied = false
        testSubject.event(AccountSetupEvent.Save)
        state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.accounts.size).isEqualTo(1)
    }

    @Test
    fun `cancel remains busy until attempt exits then permits a fresh form`() = runTest {
        val dispatched = CompletableDeferred<URI>()
        val testSubject = model(backgroundScope, OAuthBrowser { dispatched.complete(it) })
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        val uri = realWait { dispatched.await() }
        testSubject.event(AccountSetupEvent.Cancel)
        assertThat(testSubject.state.value.cancelling).isTrue()
        testSubject.event(AccountSetupEvent.New)
        assertThat(testSubject.state.value.busy).isTrue()
        val state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.accounts.isEmpty()).isTrue()
        assertThat(state.message).isEqualTo("Sign-in cancelled.")
        assertClosed(uri)
        testSubject.event(AccountSetupEvent.New)
        assertThat(testSubject.state.value.editing).isTrue()
        assertThat(testSubject.state.value.text(AccountField.NAME)).isEqualTo("")
    }

    @Test
    fun `closing setup cancels browser return and prevents later state publication`() = runTest {
        val dispatched = CompletableDeferred<URI>()
        val testSubject = model(backgroundScope, OAuthBrowser { dispatched.complete(it) })
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        val uri = realWait { dispatched.await() }
        testSubject.close()
        assertClosed(uri)
        testSubject.event(AccountSetupEvent.New)
        assertThat(testSubject.state.value.editing).isFalse()
        assertThat(accounts.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `reauthorization cancellation preserves existing account and protected grant`() = runTest {
        val old = providers.candidate("controlled", "Existing", "fixture@example.invalid", null)
        accounts.createWithOAuth(old, OAuthTokens("old-access", "old-refresh", 500000))
        val dispatched = CompletableDeferred<URI>()
        val testSubject = model(backgroundScope, OAuthBrowser { dispatched.complete(it) })
        testSubject.event(AccountSetupEvent.Edit(old.id))
        assertThat(testSubject.state.value.providerId).isEqualTo("controlled")
        testSubject.event(AccountSetupEvent.Save)
        realWait { dispatched.await() }
        testSubject.event(AccountSetupEvent.Cancel)
        realWait { testSubject.state.first { !it.busy } }
        assertThat(accounts.findById(old.id)).isEqualTo(old)
        assertThat(raw.values.keys).isEqualTo(setOf(old.credentialKey))
    }

    @Test
    fun `stale edit snapshot cannot replace a newer account before browser opens`() = runTest {
        val old = providers.candidate("controlled", "Existing", "fixture@example.invalid", null)
        accounts.createWithOAuth(old, OAuthTokens("old-access", "old-refresh", 500000))
        val testSubject = model(backgroundScope, OAuthBrowser { error("Stale edit must not dispatch") })
        testSubject.event(AccountSetupEvent.Edit(old.id))
        accounts.updateSync(old.id) { it.copy(displayName = "Changed elsewhere") }
        testSubject.event(AccountSetupEvent.Save)
        val state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.editing).isTrue()
        assertThat(accounts.findById(old.id)!!.displayName).isEqualTo("Changed elsewhere")
        assertThat(raw.values.keys).isEqualTo(setOf(old.credentialKey))
    }

    @Test
    fun `cancel during protected commit waits and reports saved account`() = runTest {
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
        val testSubject = model(backgroundScope)
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        try {
            realWait { entered.await() }
            testSubject.event(AccountSetupEvent.Cancel)
            assertThat(testSubject.state.value.busy).isTrue()
            assertThat(testSubject.state.value.message.orEmpty().contains("Completing")).isTrue()
        } finally {
            release.countDown()
        }
        val state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.accounts.size).isEqualTo(1)
        assertThat(state.message.orEmpty().contains("signed in")).isTrue()
    }

    @Test
    fun `empty production registry offers no sign in providers and ignores unknown selections`() = runTest {
        val testSubject = AccountSetupViewModel(accounts, backgroundScope)
        testSubject.event(AccountSetupEvent.New)
        testSubject.event(AccountSetupEvent.SelectProvider("controlled"))
        assertThat(testSubject.state.value.providers.isEmpty()).isTrue()
        assertThat(testSubject.state.value.providerId).isEqualTo(null)
    }

    @Test
    fun `window close cancels browser wait and joins receiver cleanup`() = runTest {
        val dispatched = CompletableDeferred<URI>()
        val testSubject = model(backgroundScope, OAuthBrowser { dispatched.complete(it) })
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        val uri = realWait { dispatched.await() }
        assertThat(realWait { testSubject.flushForClose() }).isTrue()
        assertClosed(uri)
        assertThat(testSubject.state.value.busy).isFalse()
        assertThat(accounts.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `window close waits for protected account commit before disposal`() = runTest {
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
        val testSubject = model(backgroundScope)
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        realWait { entered.await() }
        val closingStarted = CompletableDeferred<Unit>()
        val closing = async {
            closingStarted.complete(Unit)
            testSubject.flushForClose()
        }
        realWait { closingStarted.await() }
        try {
            assertThat(testSubject.state.value.busy).isTrue()
            assertThat(closing.isCompleted).isFalse()
        } finally {
            release.countDown()
        }
        assertThat(realWait { closing.await() }).isTrue()
        assertThat(accounts.findAll().size).isEqualTo(1)
    }

    @Test
    fun `immediate cancel before authorization starts reports cancellation rather than failure`() = runTest {
        val testSubject = model(backgroundScope, OAuthBrowser { error("Cancelled attempt must not dispatch") })
        fill(testSubject)
        testSubject.event(AccountSetupEvent.Save)
        testSubject.event(AccountSetupEvent.Cancel)
        val state = realWait { testSubject.state.first { !it.busy } }
        assertThat(state.message).isEqualTo("Sign-in cancelled.")
        assertThat(state.accounts.isEmpty()).isTrue()
        assertThat(state.editing).isFalse()
    }

    private suspend fun <T> realWait(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        withTimeout(5000) { block() }
    }

    private fun model(
        scope: CoroutineScope,
        browser: OAuthBrowser = OAuthBrowser {
            respond(it)
        },
    ) = AccountSetupViewModel(
        accounts,
        scope,
        OAuthAccountAuthorization(
            accounts,
            providers,
            browser,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    OAuthHttpReply(
                        200,
                        "application/json",
                        """{"access_token":"new-access","refresh_token":"new-refresh",
                        "token_type":"Bearer","expires_in":3600}""".toByteArray(),
                    )
                },
            ) { 0 },
        ),
        providers,
    )

    private fun fill(model: AccountSetupViewModel) {
        model.event(AccountSetupEvent.New)
        change(model, AccountField.NAME, "Controlled")
        change(model, AccountField.ADDRESS, "fixture@example.invalid")
        model.event(AccountSetupEvent.SelectProvider("controlled"))
    }

    private fun change(model: AccountSetupViewModel, field: AccountField, text: String) =
        model.event(AccountSetupEvent.Change(field, TextFieldValue(text)))

    private fun query(uri: URI) = uri.rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
    }

    private fun respond(uri: URI, denied: Boolean = false) {
        val parameters = query(uri)
        val redirect = URI(parameters.getValue("redirect_uri"))
        val result = if (denied) "error=access_denied" else "code=controlled"
        Socket(redirect.host, redirect.port).use { socket ->
            socket.getOutputStream().write(
                (
                    "GET ${redirect.path}?state=${parameters.getValue("state")}&$result HTTP/1.1\r\n" +
                        "Host: ${redirect.authority}\r\n\r\n"
                    ).toByteArray(),
            )
        }
    }

    private fun assertClosed(uri: URI) {
        val redirect = URI(query(uri).getValue("redirect_uri"))
        assertThat(runCatching { Socket(redirect.host, redirect.port).use { } }.isFailure).isTrue()
    }
}
