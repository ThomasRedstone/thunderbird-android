package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Synthetic credentials only; uses the normal production SignOn service and the installed enforcing profile. */
object ConfinedChunkedCredentialProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3 && args[0] == PROFILE)
        check(Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000') == "$PROFILE (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        val directory = if (args[1] == "prepare") {
            require(args.size == 2)
            Files.createTempDirectory(
                parent,
                "chunk-runtime-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            ).also { path ->
                Files.writeString(
                    path.resolve("owner"),
                    UUID.randomUUID().toString(),
                    StandardOpenOption.CREATE_NEW,
                )
                Files.setPosixFilePermissions(path.resolve("owner"), PosixFilePermissions.fromString("rw-------"))
            }
        } else {
            require(args.size == 3 && args[1] in setOf("verify-remove", "cleanup"))
            requireFixture(parent, Path.of(args[2]))
        }
        println("FIXTURE_DIR=$directory")
        val root = "chunk-native-${UUID.fromString(Files.readString(directory.resolve("owner")))}"
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val store = ChunkedCredentialStore(
                SignonCredentialStore(SignonIdentityIndex(database)),
                ProtectedPayloadJournal(database),
            )
            exercise(database, store, root, args[1])
        }
        if (args[1] != "prepare") {
            requireFixture(parent, directory)
            Files.list(directory).use { paths -> paths.toList().forEach(Files::delete) }
            Files.delete(directory)
        }
        println("Enforcing protected chunks ${args[1]}: PASS")
    }

    internal fun exercise(database: MailDatabase, store: CredentialStore, root: String, mode: String) {
        val sizes = listOf(8192, 39997)
        when (mode) {
            "prepare" -> sizes.forEach { size ->
                store.write("$root-$size", bytes(size))
                repeat(3) { check(store.read("$root-$size")!!.contentEquals(bytes(size))) }
            }

            "verify-remove" -> {
                sizes.forEach { size ->
                    repeat(3) { check(store.read("$root-$size")!!.contentEquals(bytes(size))) }
                }
                store.write("$root-8192", bytes(39997))
                check(store.read("$root-8192")!!.contentEquals(bytes(39997)))
                store.write("$root-8192", bytes(8192))
                sizes.forEach { store.delete("$root-$it") }
                check(store.cleanupPending() && !store.hasPendingCleanup())
                check(database.query("SELECT count(*) FROM protected_payload_roots") { getLong(0) }.single() == 0L)
                check(database.query("SELECT count(*) FROM signon_identity_index") { getLong(0) }.single() == 0L)
            }

            "cleanup" -> sizes.forEach { store.delete("$root-$it") }

            else -> error("Unknown chunk probe mode")
        }
        check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
    }

    internal fun requireFixture(parent: Path, directory: Path): Path {
        require(directory.isAbsolute && directory.normalize() == directory && directory.parent == parent)
        require(directory.fileName.toString().startsWith("chunk-runtime-"))
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        require(directory.toRealPath().parent == parent.toRealPath())
        val allowed = setOf("owner", "mail.sqlite", "mail.sqlite-journal", "mail.sqlite-wal", "mail.sqlite-shm")
        Files.list(directory).use { stream ->
            require(
                stream.allMatch {
                    it.fileName.toString() in allowed && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
                },
            )
        }
        return directory
    }

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + size).toByte() }
    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.37"
}
