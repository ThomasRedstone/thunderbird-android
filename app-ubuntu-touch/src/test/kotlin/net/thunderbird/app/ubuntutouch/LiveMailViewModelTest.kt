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

    @Test
    fun `folder search finds cached body offline retains query on back and clears across folders`() = runTest {
        MailDatabase(directory.root.toPath().resolve("search.sqlite")).use { database ->
            val manager = manager(database)
            val repository = FakeLiveMailRepository()
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(manager.findAll().single().id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenFolder(repository.folder))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.Search("BODY"))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.messages).isEqualTo(listOf(repository.summary))
            testSubject.event(LiveMailEvent.OpenMessage(repository.summary.id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.Back)
            assertThat(testSubject.state.value.query).isEqualTo("BODY")
            testSubject.event(LiveMailEvent.Search("absent"))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.messages).isEqualTo(emptyList())
            testSubject.event(LiveMailEvent.Search(""))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.messages).isEqualTo(listOf(repository.summary))
            testSubject.event(LiveMailEvent.Back)
            assertThat(testSubject.state.value.query).isEqualTo("")
            assertThat(repository.refreshes).isEqualTo(0)
            assertThat(LiveMailEvent.Search("private words").toString()).isEqualTo("Search(redacted)")
        }
    }

    @Test
    fun `account search keeps duplicate UIDs scoped and opens actions in the result folder`() = runTest {
        MailDatabase(directory.root.toPath().resolve("account-search.sqlite")).use { database ->
            val manager = manager(database)
            val repository = FakeLiveMailRepository().apply { includeArchive = true }
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            val account = manager.findAll().single().id
            testSubject.event(LiveMailEvent.OpenAccount(account))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenFolder(repository.folder))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.Search("cached"))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.SearchScope(CachedSearchScope.ACCOUNT))
            testSubject.state.first { !it.busy }
            val hits = testSubject.state.value.searchHits
            assertThat(hits.map { it.message.id }).isEqualTo(listOf("1", "1"))
            assertThat(hits.map { it.folder.id }).isEqualTo(listOf("Archive", "INBOX"))
            val archiveHit = hits.single { it.folder.id == "Archive" }
            val foreignHit = archiveHit.copy(account = AccountIdFactory.create())
            assertThat(
                runCatching {
                    testSubject.event(LiveMailEvent.OpenSearchResult(foreignHit))
                }.isFailure,
            ).isTrue()
            testSubject.event(LiveMailEvent.OpenSearchResult(archiveHit))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.folder?.id).isEqualTo("Archive")
            assertThat(testSubject.state.value.content?.body).isEqualTo("Archive cached body")
            testSubject.event(LiveMailEvent.ChangeFlag(Flag.SEEN, true))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(repository.lastFlagFolder).isEqualTo("Archive")
            testSubject.event(LiveMailEvent.Back)
            assertThat(testSubject.state.value.folder).isEqualTo(repository.folder)
            assertThat(testSubject.state.value.searchAllFolders).isTrue()
            assertThat(testSubject.state.value.query).isEqualTo("cached")
            assertThat(testSubject.state.value.searchHits.size).isEqualTo(2)
            testSubject.event(LiveMailEvent.SearchScope(CachedSearchScope.FOLDER))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.searchHits).isEqualTo(emptyList())
            assertThat(testSubject.state.value.messages.size).isEqualTo(1)
            assertThat(repository.refreshes).isEqualTo(0)
        }
    }

    @Test
    fun `unified search routes identical folder UIDs to the selected account and restores origin`() = runTest {
        MailDatabase(directory.root.toPath().resolve("unified-search.sqlite")).use { database ->
            val manager = manager(database)
            val origin = manager.findAll().single()
            val other = origin.copy(id = AccountIdFactory.create(), credentialKey = UUID.randomUUID().toString())
            manager.createWithPasswords(other, AccountPasswords("fixture", "fixture"))
            val repository = FakeLiveMailRepository().apply { separateAccount = other.id }
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(origin.id))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.OpenFolder(repository.folder))
            testSubject.state.first { !it.busy }
            testSubject.event(LiveMailEvent.SearchScope(CachedSearchScope.ALL_ACCOUNTS))
            testSubject.state.first { !it.busy }
            val hits = testSubject.state.value.searchHits
            assertThat(hits.map { it.account }).isEqualTo(listOf(origin.id, other.id))
            assertThat(hits.map { it.message.id }).isEqualTo(listOf("1", "1"))
            testSubject.event(LiveMailEvent.OpenSearchResult(hits.last()))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.account).isEqualTo(other.id)
            assertThat(testSubject.state.value.content?.body).isEqualTo("Other account cached body")
            testSubject.event(LiveMailEvent.ChangeFlag(Flag.FLAGGED, true))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(repository.lastFlagAccount).isEqualTo(other.id)
            testSubject.event(LiveMailEvent.Respond(ResponseMode.REPLY))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(repository.lastResponseAccount).isEqualTo(other.id)
            assertThat(testSubject.state.value.composeRequest?.account).isEqualTo(other.id)
            testSubject.event(LiveMailEvent.Back)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.account).isEqualTo(origin.id)
            assertThat(testSubject.state.value.folder).isEqualTo(repository.folder)
            assertThat(testSubject.state.value.searchScope).isEqualTo(CachedSearchScope.ALL_ACCOUNTS)
            assertThat(testSubject.state.value.searchHits.size).isEqualTo(2)
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
    var lastResponseAccount: AccountId? = null
    var lastFlagAccount: AccountId? = null
    var separateAccount: AccountId? = null
    var responses = 0
    override fun respond(account: AccountId, folder: String, uid: String, mode: ResponseMode): String {
        lastResponseAccount = account
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
        lastFlagAccount = account
        lastFlagFolder = folder
        if (failFlag) error("Synthetic local write failure")
        summary = if (flag == Flag.SEEN) summary.copy(unread = !value) else summary.copy(starred = value)
        pending++
    }
    var includeArchive = false
    var lastFlagFolder: String? = null
    var refreshes = 0
    private var downloaded = false
    override fun folders(account: AccountId) =
        if (includeArchive) listOf(folder, MailFolder("Archive", "Archive")) else listOf(folder)
    override fun messages(account: AccountId, folder: String) =
        listOf(if (folder == "Archive") summary.copy(subject = "Archived") else summary)
    override fun content(
        account: AccountId,
        folder: String,
        uid: String,
    ) = MailContent(
        messages(account, folder).single(),
        when {
            account == separateAccount -> "Other account cached body"
            folder == "Archive" -> "Archive cached body"
            else -> "Cached body"
        },
        downloaded,
    )
    override fun refresh(account: AccountId, folder: String?) {
        refreshes++
        error("sensitive-server-response")
    }
    override fun download(account: AccountId, folder: String, uid: String) {
        downloaded = true
    }
}
