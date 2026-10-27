package net.thunderbird.app.ubuntutouch

import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.text.Normalizer

internal fun safeAttachmentName(value: String?): String {
    var name = Normalizer.normalize(value.orEmpty().take(MAX_INPUT_NAME), Normalizer.Form.NFC).map {
        if (it == '/' || it == '\\' || Character.getType(it) in HIDDEN_TYPES) '_' else it
    }.joinToString("").trim().trim('.').take(MAX_NAME_CHARACTERS)
    while (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) name = name.dropLast(1)
    return name.ifBlank { "attachment" }
}

/** A new private directory belongs to exactly one prepared export. No recursive cleanup of unknown contents. */
internal class AttachmentTarget private constructor(private val directory: Path, val file: Path) : AutoCloseable {
    fun open(): OutputStream = Channels.newOutputStream(
        Files.newByteChannel(
            file,
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        ),
    )

    override fun close() {
        check(!Files.isSymbolicLink(directory)) { "Attachment directory changed" }
        Files.deleteIfExists(file)
        Files.deleteIfExists(directory)
    }

    companion object {
        fun recover(root: Path, file: Path): AttachmentTarget {
            require(root.isAbsolute && file.isAbsolute && root.normalize() == root && file.normalize() == file)
            require(
                file.parent.parent == root &&
                    Regex("attachment-[A-Za-z0-9_-]{1,100}").matches(file.parent.fileName.toString()),
            )
            require(isExportFileName(file.fileName.toString()))
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            check(Files.isDirectory(file.parent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
            check(
                !Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
            )
            check(file.parent.toRealPath().parent == root.toRealPath())
            return AttachmentTarget(file.parent, file)
        }

        fun create(root: Path, name: String): AttachmentTarget {
            val child = Path.of(name)
            require(!child.isAbsolute && child.nameCount == 1 && name != "." && name != "..")
            val permissions = PosixFilePermissions.fromString("rwx------")
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(permissions))
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Attachment cache is not a directory" }
            Files.setPosixFilePermissions(root, permissions)
            val directory = Files.createTempDirectory(
                root,
                "attachment-",
                PosixFilePermissions.asFileAttribute(permissions),
            )
            return AttachmentTarget(directory, directory.resolve(child))
        }
    }
}

private const val MAX_INPUT_NAME = 1024
private const val MAX_NAME_CHARACTERS = 120
private const val MAX_NAME_BYTES = 180
private val HIDDEN_TYPES = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
)

internal fun isExportFileName(name: String): Boolean = name == safeAttachmentName(name)
