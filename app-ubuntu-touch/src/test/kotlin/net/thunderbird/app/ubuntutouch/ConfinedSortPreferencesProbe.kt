package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import net.thunderbird.feature.account.AccountIdFactory

/** Frozen 0.1.26 driver: touches only its private synthetic preference directory, never production settings. */
object ConfinedSortPreferencesProbe {
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
                    "sort-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                try {
                    val preferences = MailSortPreferences(directory.resolve("mail-sort"))
                    preferences.save(MailSort.SENDER)
                    contexts().forEach { check(preferences.read(it) == MailSort.SENDER) }
                    contexts().zip(MailSort.entries).forEach { (context, sort) -> preferences.save(sort, context) }
                    verify(directory)
                    println("Enforcing scoped preference writes, legacy defaults and private atomic files: PASS")
                    println("FIXTURE_DIR=$directory")
                } catch (failure: Throwable) {
                    cleanup(directory)
                    throw failure
                }
            }

            "reopen" -> {
                require(args.size == 3)
                val directory = requireSortFixtureDirectory(parent, Path.of(args[2]))
                try {
                    verify(directory)
                    println("Fresh-process scoped preference recovery: PASS")
                } finally {
                    cleanup(directory)
                }
            }

            else -> error("Unknown sort fixture mode")
        }
    }

    private fun verify(directory: Path) {
        val preferences = MailSortPreferences(directory.resolve("mail-sort"))
        check(preferences.read() == MailSort.SENDER)
        contexts().zip(MailSort.entries).forEach { (context, sort) -> check(preferences.read(context) == sort) }
        Files.list(directory).use { entries ->
            val files = entries.toList()
            check(files.size == 7)
            files.forEach {
                check(Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS))
                check(Files.getPosixFilePermissions(it) == PosixFilePermissions.fromString("rw-------"))
                check(it.fileName.toString().matches(Regex("mail-sort(\\.[a-f0-9]{64})?")))
            }
        }
    }

    private fun contexts(): List<MailSortContext> {
        val first = AccountIdFactory.of("00000000-0000-0000-0000-000000000001")
        val second = AccountIdFactory.of("00000000-0000-0000-0000-000000000002")
        return listOf(
            MailSortContext(CachedSearchScope.FOLDER, first, "INBOX"),
            MailSortContext(CachedSearchScope.FOLDER, first, "../Archive/\u0000" + "x".repeat(1000)),
            MailSortContext(CachedSearchScope.FOLDER, second, "INBOX"),
            MailSortContext(CachedSearchScope.ACCOUNT, first),
            MailSortContext(CachedSearchScope.ACCOUNT, second),
            MailSortContext(CachedSearchScope.ALL_ACCOUNTS),
        )
    }

    private fun cleanup(directory: Path) {
        Files.list(directory).use { files -> files.forEach { Files.delete(it) } }
        Files.delete(directory)
    }

    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.26"
}

internal fun requireSortFixtureDirectory(parent: Path, directory: Path): Path {
    require(
        directory.isAbsolute && directory.parent == parent && directory.fileName.toString().startsWith("sort-runtime-"),
    )
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    require(directory.toRealPath().parent == parent.toRealPath())
    return directory
}
