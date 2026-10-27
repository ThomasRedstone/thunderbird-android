package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CancellationException
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentExportJournalTest {
    @get:Rule val directory = TemporaryFolder()
    private val account = AccountIdFactory.create()
    private val transfer = ExportTransferReference(":1.42", "/transfers/mail_demo/export/1")

    @Test
    fun `attachment journal rejects traversal controls and unsanitized filenames`() {
        listOf("../escape", "..", ".hidden", "a\\b", "a\nb", "a\u202Eb", "").forEach { name ->
            assertThat(
                runCatching {
                    ExportLeaseRecord(
                        java.util.UUID.randomUUID(),
                        AccountIdFactory.create(),
                        "attachment-fixture/$name",
                        ExportLeasePhase.PREPARING,
                    )
                }.isFailure,
            ).isTrue()
        }
    }

    @Test
    fun `reservation and broker ownership survive reopening until the private file is released`() {
        val exports = directory.root.toPath().resolve("exports")
        val root = directory.root.toPath().resolve("journal")
        val lease = AttachmentTarget.create(exports, "message.eml")
        val testSubject = ContentExportJournal(root, exports)
        val preparing = testSubject.reserve(lease.file, account)
        assertThat(ContentExportJournal(root, exports).records()).isEqualTo(listOf(preparing))
        lease.open().use { it.write("Synthetic message body".toByteArray()) }
        val ready = testSubject.ready(preparing)
        val attached = testSubject.attach(ready, transfer)
        assertThat(ContentExportJournal(root, exports).records()).isEqualTo(listOf(attached))
        assertThat(runCatching { testSubject.ready(preparing) }.isFailure).isTrue()
        assertThat(runCatching { testSubject.forgetReleased(ready) }.isFailure).isTrue()
        assertThat(runCatching { testSubject.forgetReleased(attached) }.isFailure).isTrue()
        assertThat(runCatching { testSubject.release(ready) }.isFailure).isTrue()
        assertThat(Files.exists(testSubject.file(attached))).isTrue()
        val path = root.resolve("${attached.id}.lease")
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(Files.getPosixFilePermissions(root)).isEqualTo(PosixFilePermissions.fromString("rwx------"))
        assertThat(
            String(Files.readAllBytes(path), Charsets.ISO_8859_1).contains("Synthetic message body"),
        ).isEqualTo(false)
        assertThat(attached.toString()).isEqualTo("ExportLeaseRecord(redacted)")
        assertThat(transfer.toString()).isEqualTo("ExportTransferReference(redacted)")
        AttachmentTarget.recover(exports, testSubject.file(attached)).close()
        testSubject.forgetReleased(attached)
        assertThat(ContentExportJournal(root, exports).records()).isEqualTo(emptyList())
        Files.list(exports).use { assertThat(it.count()).isEqualTo(0L) }
    }

    @Test
    fun `preparing record survives source transaction rollback and can forget only the removed lease`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val exports = directory.root.toPath().resolve("exports")
        val root = directory.root.toPath().resolve("journal")
        val journal = ContentExportJournal(root, exports)
        MailDatabase(path).use { database ->
            val account = AccountIdFactory.create()
            val storage = PersistentBackendStorage(database, account.toString())
            storage.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            val folder = storage.getFolder("INBOX").id
            database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)", folder, "1", ByteArray(100003))
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')", folder, "1")
            var reserved: ExportLeaseRecord? = null
            var checks = 0
            val testSubject = CachedMessageExport(database, exports)
            assertThat(
                runCatching {
                    testSubject.prepare(account, "INBOX", "1", reserved = { reserved = journal.reserve(it, account) }) {
                        if (++checks == 3) throw CancellationException("Synthetic cancellation")
                    }
                }.exceptionOrNull() is CancellationException,
            ).isTrue()
            val pending = checkNotNull(reserved)
            assertThat(ContentExportJournal(root, exports).records()).isEqualTo(listOf(pending))
            journal.forgetReleased(pending)
            assertThat(journal.records()).isEqualTo(emptyList())
            assertThat(database.query("SELECT length(mime) FROM messages") { getLong(0) }.single()).isEqualTo(100003L)
        }
    }

    @Test
    fun `failed journal reservation stops export before message bytes are written`() {
        val exports = directory.root.toPath().resolve("exports")
        val root = Files.writeString(directory.root.toPath().resolve("journal"), "retained")
        val lease = AttachmentTarget.create(exports, "message.eml")
        val testSubject = ContentExportJournal(root, exports)
        assertThat(runCatching { testSubject.reserve(lease.file, account) }.isFailure).isTrue()
        assertThat(Files.exists(lease.file)).isEqualTo(false)
        assertThat(Files.readString(root)).isEqualTo("retained")
        lease.close()
    }

    @Test
    fun `corrupt records and symlink substitutions are retained without following external paths`() {
        val exports = directory.root.toPath().resolve("exports")
        val root = directory.root.toPath().resolve("journal")
        val testSubject = ContentExportJournal(root, exports)
        val lease = AttachmentTarget.create(exports, "message.eml")
        val record = testSubject.reserve(lease.file, account)
        val outside = Files.writeString(directory.root.toPath().resolve("outside"), "retained")
        Files.createSymbolicLink(lease.file, outside)
        assertThat(runCatching { testSubject.file(record) }.isFailure).isTrue()
        assertThat(runCatching { AttachmentTarget.recover(exports, lease.file) }.isFailure).isTrue()
        Files.delete(lease.file)
        val marker = root.resolve("${record.id}.lease")
        Files.write(marker, ByteArray(5000))
        assertThat(runCatching { testSubject.records() }.isFailure).isTrue()
        assertThat(Files.size(marker)).isEqualTo(5000L)
        assertThat(Files.readString(outside)).isEqualTo("retained")
        lease.close()
    }

    @Test
    fun `cleanup refuses stale ownership and never recursively deletes unexpected directory contents`() {
        val exports = directory.root.toPath().resolve("exports")
        val testSubject = ContentExportJournal(directory.root.toPath().resolve("journal"), exports)
        val target = AttachmentTarget.create(exports, "message.eml")
        val record = testSubject.reserve(target.file, account)
        target.open().use { it.write(1) }
        val sentinel = Files.writeString(target.file.parent.resolve("unowned"), "retained")
        assertThat(runCatching { testSubject.release(record) }.isFailure).isTrue()
        assertThat(Files.readString(sentinel)).isEqualTo("retained")
        assertThat(testSubject.records()).isEqualTo(listOf(record))
        Files.delete(sentinel)
        testSubject.release(record)
        assertThat(testSubject.records()).isEqualTo(emptyList())
    }

    @Test
    fun `late reservation and invalid references cannot claim existing private content`() {
        val exports = directory.root.toPath().resolve("exports")
        val testSubject = ContentExportJournal(directory.root.toPath().resolve("journal"), exports)
        val lease = AttachmentTarget.create(exports, "message.eml")
        lease.open().use { it.write(1) }
        assertThat(runCatching { testSubject.reserve(lease.file, account) }.isFailure).isTrue()
        assertThat(testSubject.records()).isEqualTo(emptyList())
        assertThat(
            runCatching {
                ExportTransferReference("service.name", "/transfers/mail_demo/export/1")
            }.isFailure,
        ).isTrue()
        assertThat(runCatching { ExportTransferReference(":1.42", "/transfers/mail_demo/import/1") }.isFailure).isTrue()
        assertThat(
            runCatching {
                ExportLeaseRecord(
                    java.util.UUID.randomUUID(),
                    account,
                    "../outside/message.eml",
                    ExportLeasePhase.PREPARING,
                )
            }.isFailure,
        ).isTrue()
        lease.close()
    }
}
