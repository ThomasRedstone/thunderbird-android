package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import net.thunderbird.feature.account.AccountIdFactory

/** Synthetic private account/folder metadata only; no credentials or network are accessed. */
object ConfinedFolderRoleProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3)
        val profile = "mail-demo.thomasredstone_mail-demo_0.1.34"
        check(args[0] == profile)
        check(Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000') == "$profile (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        when (args[1]) {
            "prepare" -> {
                require(args.size == 2)
                val directory = Files.createTempDirectory(
                    parent,
                    "folder-role-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                prepareFixture(directory)
                println("Enforcing archive/trash destination persistence: PASS")
                println("FIXTURE_DIR=$directory")
            }

            "reopen" -> {
                require(args.size == 3)
                val directory = requireFolderRoleFixtureDirectory(parent, Path.of(args[2]))
                verifyFixture(directory)
                cleanup(directory)
                println("Fresh-JVM destination recovery, connection invalidation and account cleanup: PASS")
            }

            else -> error("Unknown folder role probe mode")
        }
    }

    internal fun prepareFixture(directory: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val server = ServerSettings(
                "imap",
                "localhost",
                993,
                ConnectionSecurity.SSL_TLS_REQUIRED,
                AuthType.PLAIN,
                "fixture",
                null,
                null,
            )
            val account = UtMailAccount(
                AccountIdFactory.of("00000000-0000-0000-0000-000000000001"),
                "Fixture",
                "fixture@example.invalid",
                server,
                server.copy(type = "smtp", port = 465),
                "fixture-key",
            )
            AccountSettingsStorage(database).insert(account)
            PersistentBackendStorage(database, account.id.toString()).createFolderUpdater().use {
                it.createFolders(
                    listOf(
                        FolderInfo("Archive", "Archive", FolderType.REGULAR),
                        FolderInfo("Trash", "Trash", FolderType.REGULAR),
                    ),
                )
            }
            val store = MailFolderRoleStore(database)
            check(store.read(account).isEmpty())
            store.configure(account, MailFolderRole.ARCHIVE, "Archive")
            store.configure(account, MailFolderRole.TRASH, "Trash")
            check(store.read(account).size == 2)
        }
    }

    internal fun verifyFixture(directory: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
            val accounts = AccountSettingsStorage(database)
            val original = accounts.all().single()
            val store = MailFolderRoleStore(database)
            check(store.read(original) == mapOf(MailFolderRole.ARCHIVE to "Archive", MailFolderRole.TRASH to "Trash"))
            val changed = original.copy(incoming = original.incoming.copy(host = "changed.example.invalid"))
            accounts.update(changed)
            check(store.read(changed).isEmpty())
            check(runCatching { store.configure(original, MailFolderRole.ARCHIVE, "Trash") }.isFailure)
            store.configure(changed, MailFolderRole.TRASH, "Trash")
            check(store.read(changed) == mapOf(MailFolderRole.TRASH to "Trash"))
            store.configure(changed, MailFolderRole.TRASH, null)
            check(store.read(changed).isEmpty())
            accounts.delete(changed.id)
            check(database.query("SELECT count(*) FROM metadata") { getLong(0) }.single() == 0L)
        }
    }

    private fun cleanup(directory: Path) {
        val owned = setOf("mail.sqlite", "mail.sqlite-journal", "mail.sqlite-wal", "mail.sqlite-shm")
        Files.list(directory).use { stream ->
            val paths = stream.toList()
            check(paths.all { it.fileName.toString() in owned && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) })
            paths.forEach(Files::delete)
        }
        Files.delete(directory)
    }
}

internal fun requireFolderRoleFixtureDirectory(parent: Path, directory: Path): Path {
    require(directory.isAbsolute && directory.normalize() == directory && directory.parent == parent)
    require(directory.fileName.toString().startsWith("folder-role-runtime-"))
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    require(directory.toRealPath().parent == parent.toRealPath())
    return directory
}
