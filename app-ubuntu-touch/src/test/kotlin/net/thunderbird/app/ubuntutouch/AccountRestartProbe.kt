package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import net.thunderbird.feature.account.AccountIdFactory

/** Explicit test executable, never shipped. Two JVM invocations exercise the real account/SignOn path. */
object AccountRestartProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val directory = Path.of(args[1])
        require(directory.isAbsolute && directory.fileName.toString().startsWith("account-probe-"))
        when (args[0]) {
            "prepare" -> prepare(directory)
            "verify-remove" -> verifyAndRemove(directory)
            else -> error("Unknown probe operation")
        }
    }

    private fun prepare(directory: Path) {
        check(!Files.exists(directory)) { "Probe directory must be absent" }
        Files.createDirectory(
            directory,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        Files.writeString(directory.resolve("PROBE-MARKER"), MARKER)
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val testSubject = manager(database)
            val account = UtMailAccount(
                AccountIdFactory.create(),
                "Restart fixture",
                "fixture@example.invalid",
                server("imap", 993),
                server("smtp", 465),
                UUID.randomUUID().toString(),
            )
            testSubject.createWithPasswords(account, AccountPasswords("fixture-incoming", "fixture-outgoing"))
            check(testSubject.findAll().size == 1)
        }
        println("Account and protected credentials prepared for a separate JVM restart")
    }

    private fun verifyAndRemove(directory: Path) {
        check(Files.readString(directory.resolve("PROBE-MARKER")) == MARKER)
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val testSubject = manager(database)
            val account = testSubject.findAll().single()
            check(account.displayName == "Restart fixture" && account.address == "fixture@example.invalid")
            val passwords = testSubject.passwords(account.id)
            check(passwords.incoming == "fixture-incoming" && passwords.outgoing == "fixture-outgoing")
            check(
                testSubject.replaceAccountAndPasswords(
                    account.copy(displayName = "Edited fixture"),
                    AccountPasswords("replacement-in", "replacement-out"),
                ),
            )
            check(testSubject.passwords(account.id).incoming == "replacement-in")
            testSubject.delete(account.id)
            check(testSubject.findAll().isEmpty())
            check(testSubject.cleanupUnusedCredentials())
            check(database.query("SELECT COUNT(*) FROM signon_identity_index") { getLong(0) }.single() == 0L)
        }
        directory.toFile().deleteRecursively()
        println("Account restart/read/edit/password replacement/removal passed")
    }

    private fun manager(database: MailDatabase) = UtAccountManager(
        AccountSettingsStorage(database),
        SignonCredentialStore(SignonIdentityIndex(database)),
    )

    private fun server(kind: String, port: Int) = ServerSettings(
        kind,
        "localhost",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture",
        null,
        null,
    )

    private const val MARKER = "Mail account restart fixture v1"
}
