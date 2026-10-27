package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveMailViewModelTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `cached navigation is offline and failed refresh preserves readable mail`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val repository = FakeLiveMailRepository()
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(manager.findAll().single().id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenFolder(repository.folder))
            testSubject.state.first { !it.busy }
            assertThat(repository.refreshes).isEqualTo(0)
            testSubject.event(LiveMailEvent.Refresh)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.messages).isEqualTo(listOf(repository.summary))
            assertThat(testSubject.state.value.notice.orEmpty().contains("Cached mail is retained")).isTrue()
            assertThat(testSubject.state.value.notice.orEmpty().contains("sensitive-server-response")).isFalse()
            testSubject.event(LiveMailEvent.OpenMessage(repository.summary.id))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.content?.body).isEqualTo("Cached body")
            assertThat(repository.refreshes).isEqualTo(1)
            testSubject.event(LiveMailEvent.Download)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.content?.complete).isEqualTo(true)
            testSubject.event(LiveMailEvent.Back)
            assertThat(testSubject.state.value.content).isEqualTo(null)
            assertThat(testSubject.state.value.folder).isEqualTo(repository.folder)
        }
    }

    @Test
    fun `busy operation prevents account and folder changes`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val testSubject = LiveMailViewModel(manager, FakeLiveMailRepository(), backgroundScope)
            val account = manager.findAll().single().id
            testSubject.event(LiveMailEvent.OpenAccount(account))
            assertThat(testSubject.state.value.busy).isTrue()
            testSubject.event(LiveMailEvent.Back)
            assertThat(testSubject.state.value.account).isEqualTo(account)
            testSubject.state.first { !it.busy }
        }
    }

    @Test
    fun `local flags update reader without networking and discarding needs explicit confirmation`() = runTest {
        MailDatabase(directory.root.toPath().resolve("flags.sqlite")).use { database ->
            val manager = manager(database)
            val repository = FakeLiveMailRepository()
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(manager.findAll().single().id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenFolder(repository.folder))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenMessage(repository.summary.id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.ChangeFlag(Flag.SEEN, true))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(testSubject.state.value.content?.summary?.unread).isEqualTo(false)
            assertThat(testSubject.state.value.pendingActions).isEqualTo(1)
            assertThat(repository.refreshes).isEqualTo(0)
            testSubject.event(LiveMailEvent.ConfirmDiscard)
            assertThat(repository.pending).isEqualTo(1)
            testSubject.event(LiveMailEvent.DiscardPending)
            testSubject.event(LiveMailEvent.CancelDiscard)
            testSubject.event(LiveMailEvent.ConfirmDiscard)
            assertThat(repository.pending).isEqualTo(1)
            testSubject.event(LiveMailEvent.DiscardPending)
            testSubject.event(LiveMailEvent.ConfirmDiscard)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.pendingActions).isEqualTo(0)
            assertThat(repository.refreshes).isEqualTo(0)
            repository.failFlag = true
            testSubject.event(LiveMailEvent.ChangeFlag(Flag.FLAGGED, true))
            assertThat(testSubject.flushForClose()).isFalse()
            assertThat(testSubject.state.value.content?.summary?.starred).isEqualTo(false)
            assertThat(testSubject.state.value.notice != null).isTrue()
            assertThat(testSubject.flushForClose()).isTrue()
        }
    }

    @Test
    fun `response creates one local compose request and close waits for the saved draft`() = runTest {
        MailDatabase(directory.root.toPath().resolve("response.sqlite")).use { database ->
            val manager = manager(database)
            val repository = FakeLiveMailRepository()
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            val account = manager.findAll().single().id
            testSubject.event(LiveMailEvent.OpenAccount(account))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenFolder(repository.folder))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenMessage(repository.summary.id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.Respond(ResponseMode.REPLY_ALL))
            testSubject.event(LiveMailEvent.Respond(ResponseMode.REPLY_ALL))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(repository.responses).isEqualTo(1)
            assertThat(testSubject.state.value.composeRequest).isEqualTo(ComposeRequest(account, "response-draft"))
            assertThat(repository.refreshes).isEqualTo(0)
        }
    }

    private fun manager(database: MailDatabase): UtAccountManager {
        val settings = ServerSettings(
            "imap",
            "localhost",
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        return UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore()).apply {
            createWithPasswords(
                UtMailAccount(
                    AccountIdFactory.create(),
                    "Fixture",
                    "fixture@example.invalid",
                    settings,
                    settings.copy(type = "smtp", port = 465),
                    UUID.randomUUID().toString(),
                ),
                AccountPasswords("fixture", "fixture"),
            )
        }
    }
}

private class FakeLiveMailRepository : LiveMailRepository {
    val folder = MailFolder("INBOX", "Inbox")
    var summary = MailSummary("1", "Fixture", "Sender", true)
    var responses = 0
    override fun respond(account: AccountId, folder: String, uid: String, mode: ResponseMode): String {
        responses++
        return "response-draft"
    }
    var failFlag = false
    var pending = 0
    override fun pendingFlags(account: AccountId) = pending
    override fun discardFlags(account: AccountId) {
        pending = 0
    }
    override fun changeFlag(account: AccountId, folder: String, uid: String, flag: Flag, value: Boolean) {
        if (failFlag) error("Synthetic local write failure")
        summary = if (flag == Flag.SEEN) summary.copy(unread = !value) else summary.copy(starred = value)
        pending++
    }
    var refreshes = 0
    private var downloaded = false
    override fun folders(account: AccountId) = listOf(folder)
    override fun messages(account: AccountId, folder: String) = listOf(summary)
    override fun content(
        account: AccountId,
        folder: String,
        uid: String,
    ) = MailContent(summary, "Cached body", downloaded)
    override fun refresh(account: AccountId, folder: String?) {
        refreshes++
        error("sensitive-server-response")
    }
    override fun download(account: AccountId, folder: String, uid: String) {
        downloaded = true
    }
}
