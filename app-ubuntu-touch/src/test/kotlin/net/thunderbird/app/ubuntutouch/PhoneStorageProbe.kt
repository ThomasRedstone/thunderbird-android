package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import java.nio.file.Files
import java.nio.file.Path

/** Explicit test executable; never packaged in the app. Uses and removes only its own temporary directory. */
object PhoneStorageProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = Files.createTempDirectory(Path.of(args.single()), "mail-storage-probe-")
        try {
            val path = directory.resolve("mail.sqlite")
            MailDatabase(path).use { database ->
                val storage = PersistentBackendStorage(database, "probe-account")
                storage.createFolderUpdater().use {
                    it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
                }
                storage.getFolder("INBOX").setFolderExtraNumber("uidValidity", 42)
            }
            MailDatabase(path).use { database ->
                val storage = PersistentBackendStorage(database, "probe-account")
                check(storage.getFolder("INBOX").getFolderExtraNumber("uidValidity") == 42L)
                check(PersistentBackendStorage(database, "other-account").getFolderServerIds().isEmpty())
            }
            println("SQLite native load, persistent reopen and account isolation passed")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
