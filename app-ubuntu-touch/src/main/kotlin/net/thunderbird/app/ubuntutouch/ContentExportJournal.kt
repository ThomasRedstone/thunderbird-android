package net.thunderbird.app.ubuntutouch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory

internal enum class ExportLeasePhase(val hasTransfer: Boolean) {
    PREPARING(false),
    READY(false),
    ATTACHED(true),
    CHARGING(true),
}

internal data class ExportTransferReference(val owner: String, val path: String) {
    init {
        require(owner.length <= MAX_OWNER_LENGTH && Regex(":[0-9]+\\.[0-9]+").matches(owner))
        require(path.length <= MAX_TRANSFER_PATH && Regex("/transfers/[A-Za-z0-9_]+/export/[0-9]+").matches(path))
    }
    override fun toString(): String = "ExportTransferReference(redacted)"
}

internal data class ExportLeaseRecord(
    val id: UUID,
    val account: AccountId,
    val relativeFile: String,
    val phase: ExportLeasePhase,
    val transfer: ExportTransferReference? = null,
) {
    init {
        val parts = relativeFile.split('/')
        require(parts.size == 2 && Regex("attachment-[A-Za-z0-9_-]{1,100}").matches(parts[0]))
        require(isExportFileName(parts[1]))
        require(phase.hasTransfer == (transfer != null))
    }
    override fun toString(): String = "ExportLeaseRecord(redacted)"
}

/** Single-owner journal, durable before any message bytes are written or handed to a broker. IO callers only. */
internal class ContentExportJournal(private val root: Path, private val exports: Path) {
    init {
        require(root.isAbsolute && exports.isAbsolute && root.normalize() == root && exports.normalize() == exports)
        require(!root.startsWith(exports) && !exports.startsWith(root))
    }

    @Synchronized
    fun reserve(file: Path, account: AccountId): ExportLeaseRecord {
        require(file.isAbsolute && file.normalize() == file && file.parent.parent == exports)
        check(Files.isDirectory(exports, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.parent))
        check(file.parent.toRealPath().parent == exports.toRealPath())
        check(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { "Export reservation must precede message bytes" }
        val record =
            ExportLeaseRecord(
                UUID.randomUUID(),
                account,
                exports.relativize(file).toString(),
                ExportLeasePhase.PREPARING,
            )
        check(!Files.exists(root.resolve("${record.id}.lease"), LinkOption.NOFOLLOW_LINKS))
        write(record)
        return record
    }

    @Synchronized
    fun ready(record: ExportLeaseRecord): ExportLeaseRecord {
        require(record.phase == ExportLeasePhase.PREPARING)
        check(Files.isRegularFile(file(record), LinkOption.NOFOLLOW_LINKS))
        return update(record, record.copy(phase = ExportLeasePhase.READY))
    }

    @Synchronized
    fun attach(record: ExportLeaseRecord, transfer: ExportTransferReference): ExportLeaseRecord {
        require(record.phase == ExportLeasePhase.READY)
        check(Files.isRegularFile(file(record), LinkOption.NOFOLLOW_LINKS))
        return update(record, record.copy(phase = ExportLeasePhase.ATTACHED, transfer = transfer))
    }

    @Synchronized
    fun charging(record: ExportLeaseRecord): ExportLeaseRecord {
        require(record.phase == ExportLeasePhase.ATTACHED)
        return update(record, record.copy(phase = ExportLeasePhase.CHARGING))
    }

    @Synchronized
    fun records(): List<ExportLeaseRecord> {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        return Files.newDirectoryStream(root, "*.lease").use { files ->
            files.map { path -> read(UUID.fromString(path.fileName.toString().removeSuffix(".lease"))) }
        }
    }

    fun file(record: ExportLeaseRecord): Path {
        check(Files.isDirectory(exports, LinkOption.NOFOLLOW_LINKS))
        val path = exports.resolve(record.relativeFile)
        check(!Files.isSymbolicLink(path.parent) && !Files.isSymbolicLink(path))
        return path
    }

    /** The controller decides whether a broker transfer is terminal; never forget a file that still needs cleanup. */
    @Synchronized
    fun forgetReleased(record: ExportLeaseRecord) {
        check(read(record.id) == record) { "Export journal changed" }
        val path = file(record)
        check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.exists(path.parent, LinkOption.NOFOLLOW_LINKS))
        Files.delete(root.resolve("${record.id}.lease"))
        forceDirectory(root)
    }

    /** Recheck the journal before deleting bytes, under the same owner lock used for broker attachment. */
    @Synchronized
    fun release(record: ExportLeaseRecord) {
        check(read(record.id) == record) { "Export journal changed" }
        val path = file(record)
        if (Files.exists(path.parent, LinkOption.NOFOLLOW_LINKS)) AttachmentTarget.recover(exports, path).close()
        forgetReleased(record)
    }

    private fun update(expected: ExportLeaseRecord, changed: ExportLeaseRecord): ExportLeaseRecord {
        check(read(expected.id) == expected) { "Export journal changed" }
        write(changed)
        return changed
    }

    private fun read(id: UUID): ExportLeaseRecord {
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        val path = root.resolve("$id.lease")
        val bytes = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            it.readNBytes(MAX_RECORD_BYTES + 1)
        }
        require(bytes.size <= MAX_RECORD_BYTES)
        return decodeExportRecord(bytes).also { check(it.id == id) }
    }

    private fun write(record: ExportLeaseRecord) {
        val permissions = PosixFilePermissions.fromString("rwx------")
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(permissions))
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        Files.setPosixFilePermissions(root, permissions)
        val temporary = Files.createTempFile(
            root,
            ".export-journal-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        try {
            Files.write(temporary, encodeExportRecord(record))
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(
                temporary,
                root.resolve("${record.id}.lease"),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectory(root)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun forceDirectory(path: Path) = FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }

private fun encodeExportRecord(record: ExportLeaseRecord): ByteArray = ByteArrayOutputStream().use { buffer ->
    DataOutputStream(buffer).use {
        it.writeInt(EXPORT_JOURNAL_MAGIC)
        it.writeInt(EXPORT_JOURNAL_VERSION)
        it.writeUTF(record.id.toString())
        it.writeUTF(record.account.toString())
        it.writeUTF(record.relativeFile)
        it.writeUTF(record.phase.name)
        record.transfer?.let { transfer ->
            it.writeUTF(transfer.owner)
            it.writeUTF(transfer.path)
        }
    }
    buffer.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
}

private fun decodeExportRecord(bytes: ByteArray): ExportLeaseRecord =
    DataInputStream(ByteArrayInputStream(bytes)).use {
        require(it.readInt() == EXPORT_JOURNAL_MAGIC && it.readInt() == EXPORT_JOURNAL_VERSION)
        val id = UUID.fromString(it.readUTF())
        val account = AccountIdFactory.of(it.readUTF())
        val relative = it.readUTF()
        val phase = ExportLeasePhase.valueOf(it.readUTF())
        val transfer = if (phase.hasTransfer) {
            ExportTransferReference(it.readUTF(), it.readUTF())
        } else {
            null
        }
        require(it.read() == -1)
        ExportLeaseRecord(id, account, relative, phase, transfer)
    }

private const val EXPORT_JOURNAL_MAGIC = 0x554d4558
private const val EXPORT_JOURNAL_VERSION = 1
private const val MAX_RECORD_BYTES = 4096
private const val MAX_OWNER_LENGTH = 255
private const val MAX_TRANSFER_PATH = 1024
