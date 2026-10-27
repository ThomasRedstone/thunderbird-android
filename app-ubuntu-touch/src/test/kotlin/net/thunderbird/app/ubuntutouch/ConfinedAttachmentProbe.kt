package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory

/** Synthetic private storage and fake receiver only. No production accounts, credentials, bus or network access. */
object ConfinedAttachmentProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3)
        val profile = "mail-demo.thomasredstone_mail-demo_0.1.33"
        check(args[0] == profile)
        check(System.getProperty("sun.jnu.encoding").equals("UTF-8", ignoreCase = true)) {
            "UTF-8 filename locale is required"
        }
        check(Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000') == "$profile (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        when (args[1]) {
            "prepare" -> {
                require(args.size == 2)
                val directory = Files.createTempDirectory(
                    parent,
                    "attachment-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                prepareFixture(directory)
                println("Enforcing decoded attachment, Unicode filename and private journal handoff: PASS")
                println("FIXTURE_DIR=$directory")
            }

            "cleanup" -> {
                require(args.size == 3)
                cleanup(requireAttachmentFixtureDirectory(parent, Path.of(args[2])))
                println("Owned incomplete attachment fixture cleanup: PASS")
            }

            "reopen" -> {
                require(args.size == 3)
                val directory = requireAttachmentFixtureDirectory(parent, Path.of(args[2]))
                verifyFixture(directory)
                cleanup(directory)
                println("Fresh-JVM attachment bytes and uncertain journal recovery without replay: PASS")
            }

            else -> error("Unknown attachment probe mode")
        }
    }

    internal fun prepareFixture(directory: Path) {
        initialize(directory)
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            populate(database)
            val exports = directory.resolve("exports")
            val attachments = CachedAttachmentStore(database, exports)
            val selected = attachments.list(accountId(), "INBOX", "1").single()
            check(selected.displayName == NAME)
            val journal = ContentExportJournal(directory.resolve("journal"), exports)
            val broker = AttachmentProbeBroker()
            val controller = ContentExportController(
                CachedMessageExport(database, exports),
                journal,
                broker,
                { it == accountId() },
                attachments,
            )
            val ready = controller.prepare(
                accountId(),
                "INBOX",
                "1",
                authorized = { true },
                attachment = selected.reference,
            )
            check(broker.charges == 0)
            val file = journal.file(ready)
            check(file.fileName.toString() == NAME && Files.readAllBytes(file).contentEquals(bytes()))
            check(Files.getPosixFilePermissions(file) == PosixFilePermissions.fromString("rw-------"))
            check(Files.getPosixFilePermissions(file.parent) == PosixFilePermissions.fromString("rwx------"))
            val pending = checkNotNull(controller.send(ready, broker.destination).record)
            check(pending.phase == ExportLeasePhase.CHARGING && broker.charges == 1)
            check(Files.readAllBytes(journal.file(pending)).contentEquals(bytes()))
            check(Files.list(directory.resolve("mime")).use { it.count() } == 0L)
        }
    }

    internal fun verifyFixture(directory: Path) {
        initialize(directory)
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
            val exports = directory.resolve("exports")
            val journal = ContentExportJournal(directory.resolve("journal"), exports)
            val pending = journal.records().single()
            check(pending.phase == ExportLeasePhase.CHARGING && pending.account == accountId())
            check(journal.file(pending).fileName.toString() == NAME)
            check(Files.readAllBytes(journal.file(pending)).contentEquals(bytes()))
            val broker = AttachmentProbeBroker()
            val controller = ContentExportController(
                CachedMessageExport(database, exports),
                journal,
                broker,
                { true },
                CachedAttachmentStore(database, exports),
            )
            check(controller.recover().single().record == pending)
            check(broker.charges == 0)
            check(controller.discardLocalCopy(pending).record == null)
            check(journal.records().isEmpty())
            check(Files.list(exports).use { it.count() } == 0L)
        }
    }

    private fun populate(database: MailDatabase) {
        val storage = PersistentBackendStorage(database, accountId().toString())
        storage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
        }
        val raw = "Content-Type: multipart/mixed; boundary=x\r\n\r\n" +
            "--x\r\nContent-Type: text/plain\r\n\r\nBody\r\n" +
            "--x\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename*=utf-8''caf%C3%A9%20final.bin\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            Base64.getMimeEncoder().encodeToString(bytes()) + "\r\n--x--\r\n"
        val folder = storage.getFolder("INBOX").id
        database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,'1',?)", folder, raw.toByteArray())
        database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','X_DOWNLOADED_FULL')", folder)
    }

    private fun initialize(directory: Path) {
        val logger = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) = error("Attachment probe must not log account or MIME data")
        })
        initializeMailRuntime(logger, directory.resolve("mime"))
    }

    private fun cleanup(directory: Path) {
        listOf("exports", "journal", "mime").forEach { name ->
            val path = directory.resolve(name)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                Files.delete(path)
            }
        }
        val owned = setOf("mail.sqlite", "mail.sqlite-journal", "mail.sqlite-wal", "mail.sqlite-shm")
        Files.list(directory).use { stream ->
            val paths = stream.toList()
            check(paths.all { it.fileName.toString() in owned && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) })
            paths.forEach(Files::delete)
        }
        Files.delete(directory)
    }

    private fun accountId() = AccountIdFactory.of("00000000-0000-0000-0000-000000000001")
    private fun bytes() = ByteArray(65553) { (it * 31).toByte() }
    private const val NAME = "café final.bin"
}

internal fun requireAttachmentFixtureDirectory(parent: Path, directory: Path): Path {
    require(directory.isAbsolute && directory.normalize() == directory && directory.parent == parent)
    require(directory.fileName.toString().startsWith("attachment-runtime-"))
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    require(directory.toRealPath().parent == parent.toRealPath())
    return directory
}

private class AttachmentProbeBroker : ContentExportBroker {
    val destination = ExportDestination("synthetic.receiver", "Synthetic receiver")
    private val reference = ExportTransferReference(":1.42", "/transfers/mail_demo/export/1")
    var charges = 0
    private var started = false
    override fun destinations() = listOf(destination)
    override fun create(destination: ExportDestination) = reference
    override fun start(reference: ExportTransferReference) {
        started = true
    }
    override fun state(reference: ExportTransferReference): ExportBrokerState {
        check(started) { "Receiver unavailable after restart" }
        return ExportBrokerState.IN_PROGRESS
    }
    override fun charge(reference: ExportTransferReference, file: Path) {
        charges++
        error("Synthetic reply loss")
    }
    override fun abort(reference: ExportTransferReference) = error("Synthetic receiver unavailable")
}
