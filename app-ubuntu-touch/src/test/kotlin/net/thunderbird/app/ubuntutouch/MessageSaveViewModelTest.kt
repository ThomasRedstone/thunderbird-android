package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageSaveViewModelTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `receiver discovery failure keeps prepared export visible and refresh can retry selection`() = runTest {
        fixture { database, accounts, controller, broker ->
            val testSubject = MessageSaveViewModel(controller, accounts, backgroundScope)
            testSubject.state.first { !it.busy }
            broker.discoveryFails = true
            testSubject.event(MessageSaveEvent.Prepare(accounts.findAll().single().id, "INBOX", "1"))
            val failed = testSubject.state.first { !it.busy }
            assertThat(failed.pending?.record?.phase).isEqualTo(ExportLeasePhase.READY)
            assertThat(failed.notice.orEmpty().contains("sensitive")).isEqualTo(false)
            assertThat(broker.charges).isEqualTo(0)
            broker.discoveryFails = false
            testSubject.event(MessageSaveEvent.Refresh)
            val ready = testSubject.state.first { !it.busy }
            assertThat(ready.destinations).isEqualTo(listOf(broker.destination))
            testSubject.event(MessageSaveEvent.Select(broker.destination))
            val sent = testSubject.state.first { !it.busy }
            assertThat(sent.pending?.state).isEqualTo(ExportBrokerState.CHARGED)
            assertThat(broker.charges).isEqualTo(1)
            assertThat(database.query("SELECT count(*) FROM messages") { getLong(0) }.single()).isEqualTo(1L)
        }
    }

    @Test
    fun `account mutation after prepare prevents transfer and leaves cached message`() = runTest {
        fixture { _, accounts, controller, broker ->
            val testSubject = MessageSaveViewModel(controller, accounts, backgroundScope)
            testSubject.state.first { !it.busy }
            val account = accounts.findAll().single()
            testSubject.event(MessageSaveEvent.Prepare(account.id, "INBOX", "1"))
            testSubject.state.first { !it.busy }
            accounts.updateSync(account.id) { it.copy(displayName = "Changed") }
            testSubject.event(MessageSaveEvent.Select(broker.destination))
            assertThat(testSubject.state.first { !it.busy }.pending?.record).isEqualTo(null)
            assertThat(broker.charges).isEqualTo(0)
            assertThat(runCatching { accounts.exportHandoff(account) { error("must not run") } }.isFailure).isTrue()
        }
    }

    private suspend fun fixture(
        work: suspend (MailDatabase, UtAccountManager, ContentExportController, SaveBroker) -> Unit,
    ) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val accounts = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val account = UtMailAccount(
                AccountIdFactory.create(),
                "Fixture",
                "fixture@example.invalid",
                server("imap", 993),
                server("smtp", 465),
                "fixture-key",
            )
            accounts.createWithPasswords(account, AccountPasswords("fixture", "fixture"))
            val storage = PersistentBackendStorage(database, account.id.toString())
            storage.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            val folder = storage.getFolder("INBOX").id
            database.execute(
                "INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)",
                folder,
                "1",
                "Fixture".toByteArray(),
            )
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')", folder, "1")
            val exports = directory.root.toPath().resolve("exports")
            val broker = SaveBroker()
            val controller = ContentExportController(
                CachedMessageExport(database, exports),
                ContentExportJournal(directory.root.toPath().resolve("journal"), exports),
                broker,
                { accounts.findById(it) != null },
            )
            work(database, accounts, controller, broker)
        }
    }

    private fun server(kind: String, port: Int) = ServerSettings(
        kind,
        "localhost",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture",
        null,
        null,
    )
}

private class SaveBroker : ContentExportBroker {
    val destination = ExportDestination("fixture", "Fixture")
    var discoveryFails = false
    var charges = 0
    var current = ExportBrokerState.IN_PROGRESS
    override fun destinations(): List<ExportDestination> {
        check(!discoveryFails) { "sensitive-receiver-details" }
        return listOf(destination)
    }
    override fun create(
        destination: ExportDestination,
    ) = ExportTransferReference(":1.42", "/transfers/fixture/export/1")
    override fun start(reference: ExportTransferReference) = Unit
    override fun charge(
        reference: ExportTransferReference,
        file: Path,
    ) {
        charges++
        current = ExportBrokerState.CHARGED
    }
    override fun state(reference: ExportTransferReference) = current
    override fun abort(reference: ExportTransferReference) {
        current = ExportBrokerState.ABORTED
    }
}
