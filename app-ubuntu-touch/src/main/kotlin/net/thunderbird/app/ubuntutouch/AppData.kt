package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal fun mailDatabasePath(): Path {
    val configured = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
    val base = configured?.takeIf { it.isAbsolute } ?: Path.of(System.getProperty("user.home"), ".local", "share")
    val directory = base.resolve("mail-demo.thomasredstone")
    Files.createDirectories(
        directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    return directory.resolve("mail.sqlite")
}
