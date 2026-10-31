package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSearchViewModelTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var database: MailDatabase
    private lateinit var accounts: UtAccountManager
    private lateinit var account: UtMailAccount
    private lateinit var operations: SearchUiFixture
    private val folder = MailFolder("INBOX", "Inbox")

    @Before
    fun setup() {
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        accounts = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
        val incoming = ServerSettings(
            "imap",
            "fixture.example.invalid",
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        account = UtMailAccount(
            AccountIdFactory.create(),
            "Fixture",
            "fixture@example.invalid",
            incoming,
            incoming.copy(type = "smtp", port = 465),
            "fixture",
        )
        accounts.createWithPasswords(account, AccountPasswords("incoming", "outgoing"))
        operations = SearchUiFixture(account)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun `entering is offline and reader back preserves search results without another query`() = runTest {
        val testSubject = ServerSearchViewModel(operations, accounts, backgroundScope)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        assertThat(operations.searches).isEqualTo(0)
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Needle")))
        testSubject.event(ServerSearchEvent.Search)
        val results = idle(testSubject).results
        assertThat(results?.messages?.single()?.id).isEqualTo("42")
        testSubject.event(ServerSearchEvent.Read("foreign"))
        assertThat(operations.reads).isEqualTo(0)
        testSubject.event(ServerSearchEvent.Read("42"))
        assertThat(idle(testSubject).content?.body).isEqualTo("Controlled body")
        testSubject.event(ServerSearchEvent.Back)
        assertThat(testSubject.state.value.content).isNull()
        assertThat(testSubject.state.value.results).isEqualTo(results)
        assertThat(operations.searches).isEqualTo(1)
        assertThat(operations.reads).isEqualTo(1)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        assertThat(testSubject.state.value.results).isEqualTo(results)
    }

    @Test
    fun `query edits and mode changes invalidate results while invalid input never contacts server`() = runTest {
        val testSubject = ServerSearchViewModel(operations, accounts, backgroundScope)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        testSubject.event(ServerSearchEvent.Search)
        assertThat(operations.searches).isEqualTo(0)
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Needle")))
        testSubject.event(ServerSearchEvent.Keyboard(MaliitTextEdit.Enter))
        assertThat(idle(testSubject).results != null).isTrue()
        assertThat(testSubject.state.value.input.text).isEqualTo("Needle")
        testSubject.event(ServerSearchEvent.FullText)
        assertThat(testSubject.state.value.results).isNull()
        assertThat(testSubject.state.value.fullText).isTrue()
        testSubject.event(ServerSearchEvent.Search)
        idle(testSubject)
        assertThat(operations.lastQuery?.fullText).isEqualTo(true)
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Changed")))
        assertThat(testSubject.state.value.results).isNull()
    }

    @Test
    fun `cancellation rejects late successful results and waits for owned cleanup`() = runTest {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        operations.beforeSearch = { operation ->
            val resource = AutoCloseable { released.countDown() }
            operation.register(resource)
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS))
            operation.release(resource)
        }
        val testSubject = ServerSearchViewModel(operations, accounts, backgroundScope)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Needle")))
        testSubject.event(ServerSearchEvent.Search)
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        testSubject.event(ServerSearchEvent.Cancel)
        val state = idle(testSubject)
        assertThat(state.results).isNull()
        assertThat(state.busy).isFalse()
        assertThat(state.notice).isEqualTo("Cancelled. Cached mail is retained.")
        assertThat(testSubject.flushForClose()).isTrue()
        operations.beforeSearch = {}
        testSubject.event(ServerSearchEvent.Search)
        val retried = idle(testSubject)
        assertThat(retried.results != null).isTrue()
        assertThat(retried.notice).isNull()
    }

    @Test
    fun `account replacement while searching rejects the old completion`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        operations.beforeSearch = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val testSubject = ServerSearchViewModel(operations, accounts, backgroundScope)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Needle")))
        testSubject.event(ServerSearchEvent.Search)
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        try {
            accounts.replacePasswords(account.id, AccountPasswords("new incoming", "new outgoing"))
        } finally {
            release.countDown()
        }
        val state = idle(testSubject)
        assertThat(state.results).isNull()
        assertThat(state.notice != null).isTrue()
    }

    @Test
    fun `model disposal releases owned transport and ignores subsequent events`() = runTest {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        operations.beforeSearch = { operation ->
            operation.register(AutoCloseable { released.countDown() })
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS))
        }
        val testSubject = ServerSearchViewModel(operations, accounts, backgroundScope)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Needle")))
        testSubject.event(ServerSearchEvent.Search)
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        testSubject.close()
        withContext(Dispatchers.IO) { assertThat(released.await(5, TimeUnit.SECONDS)).isTrue() }
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Ignored")))
        assertThat(testSubject.state.value.input.text).isEqualTo("Needle")
    }

    @Test
    fun `account removal clears retained query results and message content`() = runTest {
        val testSubject = ServerSearchViewModel(operations, accounts, backgroundScope)
        testSubject.event(ServerSearchEvent.Open(account.id, folder))
        testSubject.event(ServerSearchEvent.Text(TextFieldValue("Needle")))
        testSubject.event(ServerSearchEvent.Search)
        idle(testSubject)
        testSubject.event(ServerSearchEvent.Read("42"))
        idle(testSubject)
        accounts.delete(account.id)
        val cleared = withContext(Dispatchers.Default) {
            withTimeout(5000) { testSubject.state.first { it.account == null && !it.busy } }
        }
        assertThat(cleared.results).isNull()
        assertThat(cleared.content).isNull()
        assertThat(cleared.input.text).isEqualTo("")
    }

    private suspend fun idle(model: ServerSearchViewModel): ServerSearchState = withContext(Dispatchers.Default) {
        withTimeout(5000) { model.state.first { !it.busy } }
    }
}

private class SearchUiFixture(private val account: UtMailAccount) : ServerSearchOperations {
    var searches = 0
    var reads = 0
    var lastQuery: ServerMailQuery? = null
    var beforeSearch: (MailOperation) -> Unit = {}
    private val message = MailSummary("42", "Fixture", "Sender", true)
    override fun search(
        account: AccountId,
        folder: String,
        query: ServerMailQuery,
        operation: MailOperation,
    ): ServerMailResults {
        searches++
        lastQuery = query
        beforeSearch(operation)
        return ServerMailResults(account, folder, 1, mailCacheBinding(this.account), listOf(message), 1)
    }
    override fun open(results: ServerMailResults, uid: String, operation: MailOperation): MailContent {
        operation.ensureActive()
        reads++
        return MailContent(message, "Controlled body", true)
    }
}
