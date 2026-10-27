package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import java.nio.file.Files
import java.nio.file.Path
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentExportControllerTest {
    @get:Rule val directory = TemporaryFolder()
    private val account = AccountIdFactory.create()
    private val destination = ExportDestination("filemanager.fixture", "Fixture receiver")

    @Test
    fun `no transfer starts before destination choice and collected files survive until finalization`() = fixture {
            database,
            exports,
            journal,
        ->
        val broker = FakeExportBroker(destination)
        val testSubject = ContentExportController(CachedMessageExport(database, exports), journal, broker, { true })
        val ready = testSubject.prepare(account, "INBOX", "1", authorized = { true })
        assertThat(broker.creates).isEqualTo(0)
        val sent = checkNotNull(testSubject.send(ready, destination).record)
        assertThat(sent.phase).isEqualTo(ExportLeasePhase.CHARGING)
        assertThat(broker.charges).isEqualTo(1)
        broker.current = ExportBrokerState.COLLECTED
        assertThat(testSubject.poll(sent.id).state).isEqualTo(ExportBrokerState.COLLECTED)
        assertThat(Files.exists(journal.file(sent))).isTrue()
        val foreign = ExportTransferReference(":1.43", broker.reference.path)
        assertThat(testSubject.observed(sent.id, foreign, ExportBrokerState.FINALIZED).record).isEqualTo(sent)
        broker.current = ExportBrokerState.FINALIZED
        assertThat(testSubject.poll(sent.id).record).isEqualTo(null)
        assertThat(journal.records()).isEqualTo(emptyList())
        assertThat(testSubject.observed(sent.id, broker.reference, ExportBrokerState.CHARGED).record).isEqualTo(null)
    }

    @Test
    fun `lost Charge reply and new controller never resend or delete an uncertain export`() = fixture {
            database,
            exports,
            journal,
        ->
        val broker = FakeExportBroker(destination).apply { loseChargeReply = true }
        val exporter = CachedMessageExport(database, exports)
        val first = ContentExportController(exporter, journal, broker, { true })
        val ready = first.prepare(account, "INBOX", "1", authorized = { true })
        val uncertain = checkNotNull(first.send(ready, destination).record)
        assertThat(uncertain.phase).isEqualTo(ExportLeasePhase.CHARGING)
        val testSubject = ContentExportController(exporter, journal, broker, { true })
        broker.unavailable = true
        assertThat(testSubject.recover().single().record).isEqualTo(uncertain)
        assertThat(Files.exists(journal.file(uncertain))).isTrue()
        assertThat(runCatching { testSubject.prepare(account, "INBOX", "1", authorized = { true }) }.isFailure).isTrue()
        broker.unavailable = false
        assertThat(testSubject.poll(uncertain.id).state).isEqualTo(ExportBrokerState.CHARGED)
        assertThat(broker.charges).isEqualTo(1)
        assertThat(testSubject.cancel(uncertain.id).record).isEqualTo(null)
        assertThat(broker.aborts).isEqualTo(1)
        assertThat(journal.records()).isEqualTo(emptyList())
    }

    @Test
    fun `local preparation can be cancelled or recovered after restart without any broker call`() = fixture {
            database,
            exports,
            journal,
        ->
        val broker = FakeExportBroker(destination)
        val exporter = CachedMessageExport(database, exports)
        val first = ContentExportController(exporter, journal, broker, { true })
        val ready = first.prepare(account, "INBOX", "1", authorized = { true })
        assertThat(first.cancel(ready.id).record).isEqualTo(null)
        first.prepare(account, "INBOX", "1", authorized = { true })
        val testSubject = ContentExportController(exporter, journal, broker, { true })
        assertThat(testSubject.recover().single().record).isEqualTo(null)
        assertThat(journal.records()).isEqualTo(emptyList())
        assertThat(broker.creates).isEqualTo(0)
        assertThat(broker.aborts).isEqualTo(0)
    }

    @Test
    fun `receiver abort during Start prevents Charge and stale destination cannot initiate a transfer`() = fixture {
            database,
            exports,
            journal,
        ->
        val broker = FakeExportBroker(destination).apply { abortOnStart = true }
        val testSubject = ContentExportController(CachedMessageExport(database, exports), journal, broker, { true })
        val ready = testSubject.prepare(account, "INBOX", "1", authorized = { true })
        assertThat(testSubject.send(ready, ExportDestination("other", "Other")).record).isEqualTo(ready)
        assertThat(broker.creates).isEqualTo(0)
        assertThat(testSubject.send(ready, destination).record).isEqualTo(null)
        assertThat(broker.charges).isEqualTo(0)
        assertThat(journal.records()).isEqualTo(emptyList())
    }

    @Test
    fun `account authorization revoked during Start aborts without exposing message bytes`() = fixture {
            database,
            exports,
            journal,
        ->
        var authorized = true
        val broker = FakeExportBroker(destination).apply { onStart = { authorized = false } }
        val testSubject = ContentExportController(CachedMessageExport(database, exports), journal, broker, { true })
        val ready = testSubject.prepare(account, "INBOX", "1", authorized = { authorized })
        val result = testSubject.send(ready, destination)
        assertThat(result.record).isEqualTo(null)
        assertThat(result.state).isEqualTo(ExportBrokerState.ABORTED)
        assertThat(broker.charges).isEqualTo(0)
        assertThat(broker.aborts).isEqualTo(1)
        assertThat(journal.records()).isEqualTo(emptyList())
    }

    private fun fixture(work: (MailDatabase, Path, ContentExportJournal) -> Unit) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val storage = PersistentBackendStorage(database, account.toString())
            storage.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            val folder = storage.getFolder("INBOX").id
            database.execute(
                "INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)",
                folder,
                "1",
                "Synthetic message".toByteArray(),
            )
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')", folder, "1")
            val exports = directory.root.toPath().resolve("exports")
            work(database, exports, ContentExportJournal(directory.root.toPath().resolve("journal"), exports))
        }
    }
}

private class FakeExportBroker(private val destination: ExportDestination) : ContentExportBroker {
    val reference = ExportTransferReference(":1.42", "/transfers/mail_demo/export/1")
    var current = ExportBrokerState.CREATED
    var creates = 0
    var charges = 0
    var aborts = 0
    var loseChargeReply = false
    var unavailable = false
    var abortOnStart = false
    var onStart: () -> Unit = {}
    override fun destinations() = listOf(destination)
    override fun create(destination: ExportDestination): ExportTransferReference {
        creates++
        return reference
    }
    override fun start(reference: ExportTransferReference) {
        current = if (abortOnStart) ExportBrokerState.ABORTED else ExportBrokerState.IN_PROGRESS
        onStart()
    }
    override fun charge(reference: ExportTransferReference, file: Path) {
        check(Files.readString(file) == "Synthetic message")
        charges++
        current = ExportBrokerState.CHARGED
        check(!loseChargeReply) { "sensitive-broker-details" }
    }
    override fun state(reference: ExportTransferReference): ExportBrokerState {
        check(!unavailable) { "sensitive-broker-details" }
        return current
    }
    override fun abort(reference: ExportTransferReference) {
        aborts++
        current = ExportBrokerState.ABORTED
    }
}
