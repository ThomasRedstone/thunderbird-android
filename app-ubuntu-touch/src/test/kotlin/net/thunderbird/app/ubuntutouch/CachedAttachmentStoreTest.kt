package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlinx.coroutines.CancellationException
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedAttachmentStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var database: MailDatabase
    private lateinit var exports: Path
    private val account = AccountIdFactory.create()
    private val foreign = AccountIdFactory.create()
    private var previous: java.io.File? = null

    @Before
    fun setup() {
        previous = BinaryTempFileBody.getTempDirectory()
        initializeMailRuntime(
            DefaultLogger(object : LogSink {
                override val level = LogLevel.VERBOSE
                override fun log(event: LogEvent) = error("Attachment access must not log MIME or names")
            }),
            directory.root.toPath().resolve("mime"),
        )
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        exports = directory.root.toPath().resolve("exports")
        seed(account)
        seed(foreign)
    }

    @After
    fun cleanup() {
        database.close()
        BinaryTempFileBody.setTempDirectory(previous)
    }

    @Test
    fun `decoded binary export is private bounded to its lease and repeatable without MIME leftovers`() {
        val testSubject = CachedAttachmentStore(database, exports)
        val info = testSubject.list(account, "INBOX", "1").single()
        assertThat(info.displayName).isEqualTo("café.bin")
        assertThat(info.reference.toString()).isEqualTo("AttachmentReference(redacted)")
        assertThat(info.toString()).isEqualTo("CachedAttachment(redacted)")
        repeat(2) {
            val prepared = testSubject.prepare(account, "INBOX", "1", info.reference)
            val path = prepared.file
            prepared.use {
                assertThat(Files.readAllBytes(path).contentEquals(byteArrayOf(0, 1, 2, 3, 4, 5))).isTrue()
                assertThat(it.sizeBytes).isEqualTo(6L)
                assertThat(it.toString()).isEqualTo("PreparedAttachment(redacted)")
                assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
                assertThat(
                    Files.getPosixFilePermissions(path.parent),
                ).isEqualTo(PosixFilePermissions.fromString("rwx------"))
                assertThat(path.startsWith(exports)).isTrue()
            }
            prepared.close()
            assertThat(Files.exists(path.parent)).isEqualTo(false)
        }
        assertClean()
    }

    @Test
    fun `context fingerprints ordinals full cache and raw size are enforced before export`() {
        val testSubject = CachedAttachmentStore(database, exports)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        assertThat(runCatching { testSubject.prepare(foreign, "INBOX", "1", reference) }.isFailure).isTrue()
        assertThat(runCatching { testSubject.prepare(account, "Other", "1", reference) }.isFailure).isTrue()
        assertThat(runCatching { testSubject.prepare(account, "INBOX", "2", reference) }.isFailure).isTrue()
        assertThat(
            runCatching {
                testSubject.prepare(account, "INBOX", "1", reference.copy(ordinal = -1))
            }.isFailure,
        ).isTrue()
        update(account, raw().replace("fixture.bin", "changed.bin"))
        assertThat(runCatching { testSubject.prepare(account, "INBOX", "1", reference) }.isFailure).isTrue()
        assertThat(
            runCatching {
                CachedAttachmentStore(database, exports, maximumRawBytes = 1).list(account, "INBOX", "1")
            }.isFailure,
        ).isTrue()
        val cached = PersistentBackendStorage(database, account.toString()).getFolder("INBOX")
        database.execute("DELETE FROM flags WHERE folder=? AND uid='1'", cached.id)
        assertThat(runCatching { testSubject.list(account, "INBOX", "1") }.isFailure).isTrue()
        assertClean()
    }

    @Test
    fun `decoded limit and cancellation remove partial exports and parsed MIME resources`() {
        val testSubject = CachedAttachmentStore(database, exports, maximumDecodedBytes = 5)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        assertThat(runCatching { testSubject.prepare(account, "INBOX", "1", reference) }.isFailure).isTrue()
        assertClean()
        val cancelled = CancellationException("Controlled cancellation")
        var checks = 0
        val caught = runCatching {
            CachedAttachmentStore(database, exports).prepare(account, "INBOX", "1", reference) {
                checks++
                if (checks == 4) throw cancelled
            }
        }.exceptionOrNull()
        assertThat(caught === cancelled).isTrue()
        assertClean()
    }

    @Test
    fun `filenames are decoded and normalized without path or hidden control characters`() {
        assertThat(safeAttachmentName("../../a\\b\n\u202Efile.bin").contains('/')).isEqualTo(false)
        assertThat(safeAttachmentName("../../a\\b\n\u202Efile.bin").contains('\\')).isEqualTo(false)
        assertThat(safeAttachmentName(".. ")).isEqualTo("attachment")
        assertThat(safeAttachmentName("é".repeat(200)).toByteArray().size <= 180).isTrue()
        assertThat(safeAttachmentName("cafe\u0301.bin")).isEqualTo("café.bin")
    }

    @Test
    fun `unsupported transfer encoding is rejected without logging or retaining an export`() {
        update(
            account,
            raw().replace("Content-Transfer-Encoding: base64", "Content-Transfer-Encoding: unknown-sensitive"),
        )
        val testSubject = CachedAttachmentStore(database, exports)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        assertThat(runCatching { testSubject.prepare(account, "INBOX", "1", reference) }.isFailure).isTrue()
        assertClean()
    }

    @Test
    fun `size limit removes an export after bytes have actually reached the private file`() {
        val bytes = ByteArray(20_000) { it.toByte() }
        update(account, raw().replace("AAECAwQF", Base64.getEncoder().encodeToString(bytes)))
        val testSubject = CachedAttachmentStore(database, exports, maximumDecodedBytes = 10_000)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        var observed = 0L
        val failure = runCatching {
            testSubject.prepare(account, "INBOX", "1", reference) {
                if (Files.exists(exports)) {
                    Files.walk(exports).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.forEach { observed = maxOf(observed, Files.size(it)) }
                    }
                }
            }
        }.exceptionOrNull()
        assertThat(failure != null).isTrue()
        assertThat(observed > 0L).isTrue()
        assertClean()
    }

    @Test
    fun `quoted printable and default transfer encoding produce original bytes`() {
        val variants = listOf(
            raw().replace("base64", "quoted-printable").replace("AAECAwQF", "caf=C3=A9=0D=0A") to
                "café\r\n".toByteArray(),
            raw().replace(
                "Content-Transfer-Encoding: base64\r\n",
                "",
            ).replace("AAECAwQF", "Raw") to "Raw".toByteArray(),
        )
        for ((raw, expected) in variants) {
            update(account, raw)
            val testSubject = CachedAttachmentStore(database, exports)
            val reference = testSubject.list(account, "INBOX", "1").single().reference
            testSubject.prepare(account, "INBOX", "1", reference).use {
                assertThat(Files.readAllBytes(it.file).contentEquals(expected)).isTrue()
            }
            assertClean()
        }
    }

    @Test
    fun `empty attachments remain valid empty files`() {
        update(account, raw().replace("AAECAwQF", ""))
        val testSubject = CachedAttachmentStore(database, exports)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        testSubject.prepare(account, "INBOX", "1", reference).use {
            assertThat(it.sizeBytes).isEqualTo(0L)
            assertThat(Files.size(it.file)).isEqualTo(0L)
        }
        assertClean()
    }

    @Test
    fun `symlinked export root is rejected without modifying its destination`() {
        val destination = directory.root.toPath().resolve("outside")
        Files.createDirectory(destination)
        val sentinel = destination.resolve("retained")
        Files.writeString(sentinel, "Keep")
        Files.createSymbolicLink(exports, destination)
        val testSubject = CachedAttachmentStore(database, exports)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        assertThat(runCatching { testSubject.prepare(account, "INBOX", "1", reference) }.isFailure).isTrue()
        assertThat(Files.readString(sentinel)).isEqualTo("Keep")
        Files.list(destination).use { assertThat(it.count()).isEqualTo(1L) }
        assertThat(directory.root.resolve("mime").listFiles().orEmpty().toList()).isEqualTo(emptyList())
    }

    @Test
    fun `attached messages export as a bounded readable eml rather than failing stream access`() {
        val raw = "Content-Type: multipart/mixed; boundary=outer\r\n\r\n" +
            "--outer\r\nContent-Type: text/plain\r\n\r\nOuter body\r\n" +
            "--outer\r\nContent-Type: message/rfc822\r\n" +
            "Content-Disposition: attachment; filename=attached.eml\r\n\r\n" +
            "From: sender@example.invalid\r\nSubject: Attached fixture\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
            "Qm9keSDOuw==\r\n--outer--\r\n"
        update(account, raw)
        val testSubject = CachedAttachmentStore(database, exports)
        val info = testSubject.list(account, "INBOX", "1").single()
        assertThat(info.mimeType).isEqualTo("message/rfc822")
        testSubject.prepare(account, "INBOX", "1", info.reference).use { prepared ->
            val bytes = Files.readAllBytes(prepared.file)
            withParsedCachedMime(bytes) { message ->
                assertThat(message.subject).isEqualTo("Attached fixture")
                assertThat(plainTextBody(message)?.trim()).isEqualTo("Body λ")
            }
        }
        assertClean()
    }

    @Test
    fun `duplicate filenames have independent leases and closing one preserves the other`() {
        val testSubject = CachedAttachmentStore(database, exports)
        val reference = testSubject.list(account, "INBOX", "1").single().reference
        testSubject.prepare(account, "INBOX", "1", reference).use { first ->
            testSubject.prepare(account, "INBOX", "1", reference).use { second ->
                assertThat(first.file != second.file).isTrue()
                first.close()
                assertThat(Files.size(second.file)).isEqualTo(6L)
            }
        }
        assertClean()
    }

    @Test
    fun `encoded traversal and newline filename remains inside a fresh private directory`() {
        update(account, raw().replace("caf%C3%A9.bin", "..%2F..%2Fescape%0A.bin"))
        val testSubject = CachedAttachmentStore(database, exports)
        val info = testSubject.list(account, "INBOX", "1").single()
        testSubject.prepare(account, "INBOX", "1", info.reference).use {
            assertThat(it.file.parent.parent).isEqualTo(exports)
            assertThat(it.file.fileName.toString()).isEqualTo(info.displayName)
            assertThat(info.displayName.none { char -> char == '/' || char == '\\' || char.isISOControl() }).isTrue()
            assertThat(Files.size(it.file)).isEqualTo(6L)
        }
        assertClean()
    }

    @Test
    fun `reader lists sanitized attachment metadata offline without mixing accounts or partial messages`() {
        val raw = FakeCredentialStore().apply { locked = true }
        val manager = UtAccountManager(AccountSettingsStorage(database), raw)
        val logger = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) = error("Reader must not log attachment metadata")
        })
        val testSubject = PersistentLiveMailRepository(
            database,
            ForegroundMailSessionFactory(manager, database, JvmTrustedSocketFactory(), logger),
        )
        val content = testSubject.content(account, "INBOX", "1")
        assertThat(content.body.trim()).isEqualTo("Body")
        assertThat(content.attachments).isEqualTo(listOf(MailAttachmentInfo("café.bin", "application/octet-stream")))
        assertThat(content.attachments.single().toString()).isEqualTo("MailAttachmentInfo(redacted)")
        update(foreign, raw().replace("caf%C3%A9.bin", "foreign.bin"))
        assertThat(testSubject.content(foreign, "INBOX", "1").attachments.single().displayName)
            .isEqualTo("foreign.bin")
        assertThat(testSubject.content(account, "INBOX", "1").attachments).isEqualTo(content.attachments)
        update(account, raw().replace("text/plain", "text/html"))
        val html = testSubject.content(account, "INBOX", "1")
        assertThat(html.plainText).isEqualTo(false)
        assertThat(html.attachments).isEqualTo(content.attachments)
        val folder = PersistentBackendStorage(database, account.toString()).getFolder("INBOX")
        database.execute("DELETE FROM flags WHERE folder=? AND flag='X_DOWNLOADED_FULL'", folder.id)
        val partial = testSubject.content(account, "INBOX", "1")
        assertThat(partial.complete).isEqualTo(false)
        assertThat(partial.attachments).isEqualTo(emptyList())
        assertClean()
    }

    @Test
    fun `attachment handoff retains decoded bytes across journal recovery and never repeats Charge`() {
        val attachments = CachedAttachmentStore(database, exports)
        val reference = attachments.list(account, "INBOX", "1").single().reference
        val journalPath = directory.root.toPath().resolve("journal")
        val journal = ContentExportJournal(journalPath, exports)
        val broker = AttachmentExportBroker()
        val testSubject = ContentExportController(
            CachedMessageExport(database, exports),
            journal,
            broker,
            { it == account },
            attachments,
        )
        val ready = testSubject.prepare(account, "INBOX", "1", authorized = { true }, attachment = reference)
        assertThat(journal.file(ready).fileName.toString()).isEqualTo("café.bin")
        assertThat(broker.charges).isEqualTo(0)
        val pending = checkNotNull(testSubject.send(ready, broker.destination).record)
        assertThat(pending.phase).isEqualTo(ExportLeasePhase.CHARGING)
        val reopened = ContentExportJournal(journalPath, exports)
        val recovered = ContentExportController(
            CachedMessageExport(database, exports),
            reopened,
            broker,
            { true },
            attachments,
        )
        assertThat(recovered.recover().single().record).isEqualTo(pending)
        assertThat(Files.readAllBytes(reopened.file(pending)).toList()).isEqualTo(listOf<Byte>(0, 1, 2, 3, 4, 5))
        assertThat(broker.charges).isEqualTo(1)
        broker.current = ExportBrokerState.FINALIZED
        assertThat(recovered.poll(pending.id).record).isEqualTo(null)
        assertThat(reopened.records()).isEqualTo(emptyList())
        assertClean()
    }

    @Test
    fun `attachment reservation precedes bytes and preparation failure leaves recoverable cleanup metadata`() {
        val attachments = CachedAttachmentStore(database, exports)
        val reference = attachments.list(account, "INBOX", "1").single().reference
        val journal = ContentExportJournal(directory.root.toPath().resolve("journal"), exports)
        val failure = runCatching {
            attachments.prepare(account, "INBOX", "1", reference, reserved = { file ->
                assertThat(Files.exists(file)).isEqualTo(false)
                journal.reserve(file, account)
                throw CancellationException("Controlled reservation interruption")
            })
        }
        assertThat(failure.isFailure).isTrue()
        assertThat(journal.records().single().phase).isEqualTo(ExportLeasePhase.PREPARING)
        val broker = AttachmentExportBroker()
        val testSubject = ContentExportController(
            CachedMessageExport(database, exports),
            journal,
            broker,
            { true },
            attachments,
        )
        assertThat(testSubject.recover().single().record).isEqualTo(null)
        assertThat(journal.records()).isEqualTo(emptyList())
        assertThat(broker.charges).isEqualTo(0)
        assertClean()
    }

    private fun assertClean() {
        assertThat(directory.root.resolve("mime").listFiles().orEmpty().toList()).isEqualTo(emptyList())
        if (Files.exists(exports)) Files.list(exports).use { assertThat(it.count()).isEqualTo(0L) }
    }

    private fun seed(id: AccountId) {
        val storage = PersistentBackendStorage(database, id.toString())
        storage.createFolderUpdater().use { it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX))) }
        val folder = storage.getFolder("INBOX")
        database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)", folder.id, "1", raw().toByteArray())
        database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')", folder.id, "1")
    }

    private fun update(id: AccountId, raw: String) {
        val folder = PersistentBackendStorage(database, id.toString()).getFolder("INBOX")
        database.execute("UPDATE messages SET mime=? WHERE folder=? AND uid='1'", raw.toByteArray(), folder.id)
    }

    private fun raw() = "Subject: Attachment fixture\r\nContent-Type: multipart/mixed; boundary=fixture\r\n\r\n" +
        "--fixture\r\nContent-Type: text/plain\r\n\r\nBody\r\n" +
        "--fixture\r\nContent-Type: application/octet-stream; name=fixture.bin\r\n" +
        "Content-Disposition: attachment; filename*=utf-8''caf%C3%A9.bin; size=1\r\n" +
        "Content-Transfer-Encoding: base64\r\n\r\nAAECAwQF\r\n--fixture--\r\n"
}

private class AttachmentExportBroker : ContentExportBroker {
    val destination = ExportDestination("receiver.fixture", "Fixture")
    private val reference = ExportTransferReference(":1.42", "/transfers/mail_demo/export/1")
    var current = ExportBrokerState.CREATED
    var charges = 0
    override fun destinations() = listOf(destination)
    override fun create(destination: ExportDestination) = reference
    override fun start(reference: ExportTransferReference) {
        current = ExportBrokerState.IN_PROGRESS
    }
    override fun charge(reference: ExportTransferReference, file: Path) {
        check(Files.readAllBytes(file).contentEquals(byteArrayOf(0, 1, 2, 3, 4, 5)))
        charges++
        current = ExportBrokerState.CHARGED
        error("Controlled lost reply")
    }
    override fun state(reference: ExportTransferReference) = current
    override fun abort(reference: ExportTransferReference) {
        current = ExportBrokerState.ABORTED
    }
}
