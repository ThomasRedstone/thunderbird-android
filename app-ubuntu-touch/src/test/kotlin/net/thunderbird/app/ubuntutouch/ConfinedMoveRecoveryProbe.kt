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
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountIdFactory

/** Runs only against synthetic private storage; never opens production accounts or a network connection. */
object ConfinedMoveRecoveryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3)
        val profile = "mail-demo.thomasredstone_mail-demo_0.1.32"
        check(args[0] == profile)
        check(Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000') == "$profile (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        when (args[1]) {
            "prepare" -> {
                require(args.size == 2)
                val directory = Files.createTempDirectory(
                    parent,
                    "move-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                prepareFixture(directory)
                println("Enforcing move claim and retained binary snapshot: PASS")
                println("FIXTURE_DIR=$directory")
            }

            "reopen" -> {
                require(args.size == 3)
                val directory = requireMoveFixtureDirectory(parent, Path.of(args[2]))
                verifyFixture(directory)
                cleanup(directory)
                println("Fresh-process uncertain recovery, retained pages and account isolation: PASS")
            }

            else -> error("Unknown move probe mode")
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
                accountId(),
                "Fixture",
                "fixture@example.invalid",
                server,
                server.copy(type = "smtp", port = 465),
                "move-fixture-key",
            )
            AccountSettingsStorage(database).insert(account)
            val binding = mailCacheBinding(account)
            val storage = PersistentBackendStorage(database, account.id.toString())
            storage.createFolderUpdater().use {
                it.createFolders(
                    listOf(
                        FolderInfo("INBOX", "Inbox", FolderType.INBOX),
                        FolderInfo("Archive", "Archive", FolderType.REGULAR),
                    ),
                )
            }
            val source = storage.getFolder("INBOX")
            source.setFolderExtraNumber("imapUidValidity", 1)
            storage.getFolder("Archive").setFolderExtraNumber("imapUidValidity", 2)
            storage.getFolder("Archive").setFolderExtraString(CACHE_BINDING_KEY, binding)
            database.execute(
                "INSERT INTO messages(folder,uid,mime,origin_binding,effective_date) VALUES(?,'1',?,?,42)",
                source.id,
                bytes(),
                binding,
            )
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','SEEN')", source.id)
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','X_DOWNLOADED_FULL')", source.id)
            val moves = MailMoveStore(database)
            val claimed = moves.claim(moves.enqueue(MailMessageKey(account.id, "INBOX", "1"), "Archive"))
            check(claimed.state == MailMoveState.WORKING && claimed.attempt == 1L)
            storage.createFolderUpdater().use { it.deleteFolders(listOf("INBOX")) }
            check(MailMovePayloadStore(database).info(account.id, claimed.id)?.bytes == bytes().size.toLong())
        }
    }

    internal fun verifyFixture(directory: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
            val moves = MailMoveStore(database)
            val recovered = moves.list(accountId()).single()
            check(recovered.state == MailMoveState.UNKNOWN && recovered.uncertain && recovered.attempt == 1L)
            check(runCatching { moves.claim(recovered) }.isFailure)
            check(runCatching { moves.cancelQueued(recovered) }.isFailure)
            val payloads = MailMovePayloadStore(database)
            check(payloads.info(accountId(), recovered.id) == MailMovePayloadInfo(bytes().size.toLong(), true, 42L))
            check(payloads.flags(accountId(), recovered.id) == setOf(Flag.SEEN, Flag.X_DOWNLOADED_FULL))
            var offset: Long? = 0L
            val expected = bytes()
            while (offset != null) {
                val current = offset
                val page = payloads.page(accountId(), recovered.id, current)
                val end = minOf(current + SOURCE_PAGE_BYTES, expected.size.toLong()).toInt()
                check(page.text == escapedSourceBytes(expected.copyOfRange(current.toInt(), end)))
                offset = page.nextOffset
            }
            val foreign = AccountIdFactory.of("00000000-0000-0000-0000-000000000002")
            check(payloads.info(foreign, recovered.id) == null)
            check(runCatching { payloads.page(foreign, recovered.id, 0) }.isFailure)
            check(runCatching { payloads.releaseAfterDestinationCache(recovered, "77") }.isFailure)
            check(payloads.info(accountId(), recovered.id) != null)
        }
    }

    private fun accountId() = AccountIdFactory.of("00000000-0000-0000-0000-000000000001")
    private fun bytes() = ByteArray(SOURCE_PAGE_BYTES * 2 + 17) { (it * 31).toByte() }
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

internal fun requireMoveFixtureDirectory(parent: Path, directory: Path): Path {
    require(directory.isAbsolute && directory.normalize() == directory && directory.parent == parent)
    require(directory.fileName.toString().startsWith("move-runtime-"))
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    require(directory.toRealPath().parent == parent.toRealPath())
    return directory
}
