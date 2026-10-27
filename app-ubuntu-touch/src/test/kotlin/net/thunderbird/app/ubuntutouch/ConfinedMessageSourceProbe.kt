package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import net.thunderbird.feature.account.AccountIdFactory

/** Frozen 0.1.27 driver; only synthetic source bytes in its own database are inspected. */
object ConfinedMessageSourceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(args[0] == PROFILE && label == "$PROFILE (enforce)") { "Required application confinement unavailable" }
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        when (args[1]) {
            "prepare" -> {
                require(args.size == 2)
                Files.createDirectories(parent)
                val directory = Files.createTempDirectory(
                    parent,
                    "source-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                try {
                    MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                        val storage = PersistentBackendStorage(database, account().toString())
                        storage.createFolderUpdater().use {
                            it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
                        }
                        val folder = storage.getFolder("INBOX").id
                        database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)", folder, "1", raw())
                        database.execute(
                            "INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')",
                            folder,
                            "1",
                        )
                    }
                    verify(directory)
                    println("Enforcing cached binary source paging, escaping and scope isolation: PASS")
                    println("FIXTURE_DIR=$directory")
                } catch (failure: Throwable) {
                    cleanup(directory)
                    throw failure
                }
            }

            "reopen" -> {
                require(args.size == 3)
                val directory = requireSourceFixtureDirectory(parent, Path.of(args[2]))
                try {
                    verify(directory)
                    println("Fresh-process cached source recovery: PASS")
                } finally {
                    cleanup(directory)
                }
            }

            else -> error("Unknown source fixture mode")
        }
    }

    private fun verify(directory: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            var offset: Long? = 0
            val text = StringBuilder()
            var pages = 0
            while (offset != null) {
                val page = cachedMessageSource(database, account(), "INBOX", "1", offset)
                check(page.complete && page.totalBytes == raw().size.toLong())
                check(page.sizeBytes <= SOURCE_PAGE_BYTES)
                text.append(page.text)
                offset = page.nextOffset
                pages++
            }
            check(pages == 3 && text.toString() == escapedSourceBytes(raw()))
            val foreign = AccountIdFactory.of("00000000-0000-0000-0000-000000000002")
            check(runCatching { cachedMessageSource(database, foreign, "INBOX", "1", 0) }.isFailure)
            check(runCatching { cachedMessageSource(database, account(), "Archive", "1", 0) }.isFailure)
            check(runCatching { cachedMessageSource(database, account(), "INBOX", "missing", 0) }.isFailure)
            check(runCatching { cachedMessageSource(database, account(), "INBOX", "1", Long.MAX_VALUE) }.isFailure)
            check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
        }
    }

    private fun account() = AccountIdFactory.of("00000000-0000-0000-0000-000000000001")
    private fun raw() = ByteArray(SOURCE_PAGE_BYTES * 2 + 17) { ((it * 31) xor (it ushr 8) xor (it ushr 16)).toByte() }
    private fun cleanup(directory: Path) {
        Files.list(directory).use { files -> files.forEach { Files.delete(it) } }
        Files.delete(directory)
    }

    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.27"
}

internal fun requireSourceFixtureDirectory(parent: Path, directory: Path): Path {
    require(
        directory.isAbsolute && directory.parent == parent &&
            directory.fileName.toString().startsWith("source-runtime-"),
    )
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    require(directory.toRealPath().parent == parent.toRealPath())
    return directory
}
