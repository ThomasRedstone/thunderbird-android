package net.thunderbird.app.ubuntutouch

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** A single public display preference, stored beside the private mail database. IO callers only. */
internal class MailSortPreferences(private val path: Path) {
    fun read(): MailSort {
        val value = try {
            Files.newInputStream(path).use {
                val bytes = it.readNBytes(MAX_VALUE_BYTES + 1)
                if (bytes.size > MAX_VALUE_BYTES) "" else bytes.toString(Charsets.UTF_8).trim()
            }
        } catch (_: NoSuchFileException) {
            return MailSort.NEWEST
        }
        return MailSort.entries.singleOrNull { it.name == value } ?: MailSort.NEWEST
    }

    fun save(sort: MailSort) {
        val temporary = Files.createTempFile(
            path.toAbsolutePath().parent,
            ".mail-sort-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        try {
            Files.writeString(temporary, sort.name)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private const val MAX_VALUE_BYTES = 64
