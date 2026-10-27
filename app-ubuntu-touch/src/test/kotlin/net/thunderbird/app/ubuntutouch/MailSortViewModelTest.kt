package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailSortViewModelTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `sorting cached folder works with locked credentials and survives reader and account navigation`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val raw = FakeCredentialStore()
            val manager = UtAccountManager(AccountSettingsStorage(database), raw)
            val account = account(manager)
            val repository = CachedRepository(account)
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            raw.locked = true
            testSubject.event(LiveMailEvent.OpenAccount(account))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            assertThat(idle(testSubject).messages.map { it.id }).isEqualTo(listOf("new", "old"))
            testSubject.event(LiveMailEvent.Sort(MailSort.SUBJECT))
            assertThat(idle(testSubject).messages.map { it.id }).isEqualTo(listOf("old", "new"))
            testSubject.event(LiveMailEvent.OpenMessage("old"))
            idle(testSubject)
            testSubject.event(LiveMailEvent.Sort(MailSort.SENDER))
            assertThat(testSubject.state.value.sort).isEqualTo(MailSort.SUBJECT)
            testSubject.event(LiveMailEvent.Back)
            testSubject.event(LiveMailEvent.Back)
            testSubject.event(LiveMailEvent.Back)
            testSubject.event(LiveMailEvent.OpenAccount(account))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            val reopened = idle(testSubject)
            assertThat(reopened.sort).isEqualTo(MailSort.SUBJECT)
            assertThat(reopened.messages.map { it.id }).isEqualTo(listOf("old", "new"))
            assertThat(repository.networkCalls).isEqualTo(0)
        }
    }

    @Test
    fun `sorting unified cached results preserves the account used to open shared UID`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val first = account(manager)
            val second = account(manager)
            val repository = CachedRepository(first)
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(first))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            idle(testSubject)
            testSubject.event(LiveMailEvent.SearchScope(CachedSearchScope.ALL_ACCOUNTS))
            idle(testSubject)
            testSubject.event(LiveMailEvent.Sort(MailSort.OLDEST))
            val state = idle(testSubject)
            assertThat(state.searchHits.size).isEqualTo(4)
            assertThat(state.searchHits.map { it.message.effectiveDate }).isEqualTo(listOf(100L, 100L, 200L, 300L))
            val hit = state.searchHits.first { it.account == second && it.message.id == "old" }
            testSubject.event(LiveMailEvent.OpenSearchResult(hit))
            idle(testSubject)
            assertThat(repository.openedAccount).isEqualTo(second)
            assertThat(repository.networkCalls).isEqualTo(0)
        }
    }

    @Test
    fun `sort selection survives a fresh mailbox model and closed database`() = runTest {
        val path = directory.root.toPath().resolve("persisted.sqlite")
        val preferencePath = directory.root.toPath().resolve("mail-sort")
        val id = MailDatabase(path).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val account = account(manager)
            val repository = CachedRepository(account)
            val testSubject = LiveMailViewModel(
                manager,
                repository,
                backgroundScope,
                MailSortPreferences(preferencePath),
            )
            testSubject.event(LiveMailEvent.OpenAccount(account))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            idle(testSubject)
            testSubject.event(LiveMailEvent.Sort(MailSort.SUBJECT))
            idle(testSubject)
            assertThat(testSubject.flushForClose()).isEqualTo(true)
            account
        }
        MailDatabase(path).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val repository = CachedRepository(id)
            val testSubject = LiveMailViewModel(
                manager,
                repository,
                backgroundScope,
                MailSortPreferences(preferencePath),
            )
            assertThat(testSubject.state.value.sort).isEqualTo(MailSort.SUBJECT)
            testSubject.event(LiveMailEvent.OpenAccount(id))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            assertThat(idle(testSubject).messages.map { it.id }).isEqualTo(listOf("old", "new"))
        }
    }

    @Test
    fun `sort save failure retains session order exposes notice and allows retry`() = runTest {
        MailDatabase(directory.root.toPath().resolve("failure.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val account = account(manager)
            val repository = CachedRepository(account)
            val path = directory.root.toPath().resolve("mail-sort")
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope, MailSortPreferences(path))
            testSubject.event(LiveMailEvent.OpenAccount(account))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            idle(testSubject)
            java.nio.file.Files.createDirectory(path)
            testSubject.event(LiveMailEvent.Sort(MailSort.SUBJECT))
            val failed = idle(testSubject)
            assertThat(failed.messages.map { it.id }).isEqualTo(listOf("old", "new"))
            assertThat(failed.notice).isEqualTo(
                "Unable to save sort order. Your selection applies until this mailbox closes. Try again.",
            )
            assertThat(testSubject.flushForClose()).isEqualTo(false)
            java.nio.file.Files.delete(path)
            testSubject.event(LiveMailEvent.Sort(MailSort.SUBJECT))
            idle(testSubject)
            assertThat(testSubject.flushForClose()).isEqualTo(true)
            assertThat(MailSortPreferences(path).read()).isEqualTo(MailSort.SUBJECT)
            assertThat(repository.networkCalls).isEqualTo(0)
        }
    }

    @Test
    fun `unified selection queues full identities counts all accounts and clears only after success`() = runTest {
        MailDatabase(directory.root.toPath().resolve("bulk.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val first = account(manager)
            val second = account(manager)
            val repository = CachedRepository(first)
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(first))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            idle(testSubject)
            testSubject.event(LiveMailEvent.SearchScope(CachedSearchScope.ALL_ACCOUNTS))
            idle(testSubject)
            testSubject.event(LiveMailEvent.Selection(MailSelectionAction.Start))
            testSubject.event(LiveMailEvent.Selection(MailSelectionAction.All))
            assertThat(testSubject.state.value.selection.keys.size).isEqualTo(4)
            repository.failBatch = true
            testSubject.event(LiveMailEvent.Selection(MailSelectionAction.ApplyFlag(Flag.SEEN, true)))
            assertThat(idle(testSubject).selection.keys.size).isEqualTo(4)
            assertThat(testSubject.flushForClose()).isEqualTo(false)
            assertThat(repository.batchKeys).isEqualTo(emptyList())
            repository.failBatch = false
            testSubject.event(LiveMailEvent.Selection(MailSelectionAction.ApplyFlag(Flag.SEEN, true)))
            val saved = idle(testSubject)
            assertThat(saved.selection).isEqualTo(MailSelection())
            assertThat(saved.pendingActions).isEqualTo(4)
            testSubject.event(LiveMailEvent.DiscardPending)
            testSubject.event(LiveMailEvent.ConfirmDiscard)
            idle(testSubject)
            assertThat(repository.discardCalls).isEqualTo(0)
            assertThat(repository.batchKeys.toSet()).isEqualTo(
                setOf(
                    MailMessageKey(first, "INBOX", "old"),
                    MailMessageKey(first, "INBOX", "new"),
                    MailMessageKey(second, "INBOX", "old"),
                    MailMessageKey(second, "INBOX", "new"),
                ),
            )
            assertThat(testSubject.flushForClose()).isEqualTo(true)
            assertThat(repository.networkCalls).isEqualTo(0)
            testSubject.event(LiveMailEvent.SyncQueuedFlags)
            val synced = idle(testSubject)
            assertThat(repository.syncAccounts.toSet()).isEqualTo(setOf(first, second))
            assertThat(synced.pendingActions).isEqualTo(0)
            assertThat(synced.notice).isEqualTo(
                "Synced 2 changed folders; 0 still need attention. Unsynced changes remain queued.",
            )
        }
    }

    @Test
    fun `queued sync can cancel a blocked owned transport and retains queued flags`() = runTest {
        MailDatabase(directory.root.toPath().resolve("cancel-sync.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val account = account(manager)
            val repository = CachedRepository(account).apply {
                batchKeys = listOf(MailMessageKey(account, "INBOX", "old"))
                blockSync = true
            }
            val testSubject = LiveMailViewModel(manager, repository, backgroundScope)
            testSubject.event(LiveMailEvent.OpenAccount(account))
            idle(testSubject)
            testSubject.event(LiveMailEvent.OpenFolder(repository.inbox))
            idle(testSubject)
            testSubject.event(LiveMailEvent.SyncQueuedFlags)
            withContext(Dispatchers.Default) {
                check(repository.syncEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertThat(testSubject.state.value.canCancel).isEqualTo(true)
            testSubject.event(LiveMailEvent.Cancel)
            val cancelled = idle(testSubject)
            assertThat(cancelled.canCancel).isEqualTo(false)
            assertThat(cancelled.pendingActions).isEqualTo(1)
            assertThat(repository.batchKeys.size).isEqualTo(1)
            assertThat(repository.syncAccounts).isEqualTo(listOf(account))
        }
    }

    private fun account(manager: UtAccountManager): AccountId {
        val id = AccountIdFactory.create()
        val server = ServerSettings(
            "imap",
            "mail.example.invalid",
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "fixture@example.invalid",
            null,
            null,
        )
        manager.createWithPasswords(
            UtMailAccount(
                id,
                "Controlled",
                "fixture@example.invalid",
                server,
                server.copy(type = "smtp", port = 465),
                UUID.randomUUID().toString(),
            ),
            AccountPasswords("incoming", "outgoing"),
        )
        return id
    }

    private suspend fun idle(model: LiveMailViewModel) = withContext(Dispatchers.Default) {
        withTimeout(5000) { model.state.first { !it.busy } }
    }
}

private class CachedRepository(private val first: AccountId) : LiveMailRepository {
    val inbox = MailFolder("INBOX", "Inbox")
    var syncAccounts = emptyList<AccountId>()
    var blockSync = false
    val syncEntered = java.util.concurrent.CountDownLatch(1)
    private val syncReleased = java.util.concurrent.CountDownLatch(1)
    override fun syncQueuedFlags(accounts: List<AccountId>, operation: MailOperation): FlagSyncResult {
        syncAccounts = accounts
        if (blockSync) {
            val resource = AutoCloseable { syncReleased.countDown() }
            operation.register(resource)
            try {
                syncEntered.countDown()
                check(syncReleased.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                operation.release(resource)
            }
        }
        operation.ensureActive()
        batchKeys = batchKeys.filterNot { it.account in accounts }
        return FlagSyncResult(accounts.size, 0)
    }
    var discardCalls = 0
    override fun discardFlags(account: AccountId) {
        discardCalls++
    }
    var batchKeys = emptyList<MailMessageKey>()
    var failBatch = false
    override fun changeFlags(keys: List<MailMessageKey>, flag: Flag, value: Boolean) {
        check(!failBatch)
        check(flag == Flag.SEEN && value)
        batchKeys = keys
    }
    override fun pendingFlags(account: AccountId) = batchKeys.count { it.account == account }
    var networkCalls = 0
    var openedAccount: AccountId? = null
    override fun folders(account: AccountId) = listOf(inbox)
    override fun messages(account: AccountId, folder: String) = listOf(
        MailSummary("new", "Zulu", "Alice", false, true, if (account == first) 200 else 300),
        MailSummary("old", "Alpha", "Zed", true, false, 100),
    )
    override fun content(account: AccountId, folder: String, uid: String): MailContent {
        openedAccount = account
        return MailContent(messages(account, folder).single { it.id == uid }, "Controlled body", true)
    }
    override fun refresh(account: AccountId, folder: String?) {
        networkCalls++
        error("No network in sorting")
    }
    override fun download(account: AccountId, folder: String, uid: String) = error("No downloads in sorting")
}
