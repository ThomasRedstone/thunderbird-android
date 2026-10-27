package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.thunderbird.feature.account.AccountIdFactory

/** Synthetic private SQLite only; no real credentials, recipient, server or production settings are read. */
object ConfinedSignatureProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(args[0] == PROFILE && label == "$PROFILE (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        when (args[1]) {
            "prepare" -> {
                require(args.size == 2)
                Files.createDirectories(parent)
                val directory = Files.createTempDirectory(
                    parent,
                    "signature-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                try {
                    prepareFixture(directory)
                    verifyFixture(directory, create = true)
                    println("Enforcing signature migration, editor save, account isolation and draft composition: PASS")
                    println("FIXTURE_DIR=$directory")
                } catch (failure: Throwable) {
                    cleanup(directory)
                    throw failure
                }
            }

            "reopen" -> {
                require(args.size == 3)
                val directory = requireSignatureFixtureDirectory(parent, Path.of(args[2]))
                try {
                    verifyFixture(directory, create = false)
                    println("Fresh-process signature settings and composed draft recovery: PASS")
                } finally {
                    cleanup(directory)
                }
            }

            else -> error("Unknown signature probe mode")
        }
    }

    internal fun prepareFixture(directory: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val storage = AccountSettingsStorage(database)
            storage.insert(account())
            storage.insert(account(foreign = true))
            OutgoingMailStore(database).create(account().id, ResponseSeed(body = "Existing draft"))
            database.execute("DROP TABLE signatures")
            database.execute("PRAGMA user_version=10")
        }
    }

    internal fun verifyFixture(directory: Path, create: Boolean) = runBlocking {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            check(database.query("PRAGMA user_version") { getLong(0) }.single() == 12L)
            check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
            val accounts = UtAccountManager(AccountSettingsStorage(database), NoProbeCredentials)
            val signatures = MailSignatureStore(database)
            val outgoing = OutgoingMailStore(database)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val editor = SignatureSettingsViewModel(accounts, signatures, scope)
            val composer = ComposerViewModel(
                accounts,
                outgoing,
                OutgoingMailSender(outgoing) { _, _ -> error("Network forbidden") },
                scope,
                signatures = signatures,
            )
            try {
                editor.event(SignatureSettingsEvent.Account(account().id))
                editor.state.first { !it.busy }
                if (create) configure(editor)
                check(editor.state.value.signature == expected())
                check(signatures.read(account(foreign = true).id) == MailSignature())
                if (create) {
                    composer.event(ComposerEvent.Account(account().id))
                    composer.state.first { !it.busy }
                    composer.event(ComposerEvent.New)
                    composer.state.first { !it.busy }
                }
                check(
                    outgoing.list(account().id).map { it.body }.toSet() ==
                        setOf("Existing draft", "\n\n-- \nFixture λ\nTeam"),
                )
                check(outgoing.list(account().id).size == 2)
                check(outgoing.list(account(foreign = true).id).isEmpty())
            } finally {
                editor.close()
                composer.close()
            }
        }
    }

    private suspend fun configure(editor: SignatureSettingsViewModel) {
        check(editor.state.value.signature == MailSignature())
        editor.event(SignatureSettingsEvent.Keyboard(MaliitTextEdit.Commit("Fixture λ")))
        editor.event(SignatureSettingsEvent.Keyboard(MaliitTextEdit.Enter))
        editor.event(SignatureSettingsEvent.Keyboard(MaliitTextEdit.Commit("Team")))
        editor.event(SignatureSettingsEvent.Enabled)
        editor.event(SignatureSettingsEvent.Forwards)
        check(editor.flushForClose())
    }

    private fun expected() = MailSignature("Fixture λ\nTeam", enabled = true, forwards = false)
    private fun account(foreign: Boolean = false): UtMailAccount {
        val suffix = if (foreign) "2" else "1"
        val server =
            ServerSettings(
                "imap",
                "localhost",
                993,
                ConnectionSecurity.SSL_TLS_REQUIRED,
                AuthType.PLAIN,
                "fixture",
                null,
                null,
            )
        return UtMailAccount(
            AccountIdFactory.of("00000000-0000-0000-0000-00000000000$suffix"),
            "Fixture",
            "fixture@example.invalid",
            server,
            server.copy(type = "smtp", port = 465),
            "signature-fixture-$suffix",
        )
    }
    private fun cleanup(directory: Path) {
        val owned = setOf("mail.sqlite", "mail.sqlite-journal", "mail.sqlite-wal", "mail.sqlite-shm")
        Files.list(directory).use { files ->
            val paths = files.toList()
            check(paths.all { it.fileName.toString() in owned && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) })
            paths.forEach(Files::delete)
        }
        Files.delete(directory)
    }
    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.32"
}

internal fun requireSignatureFixtureDirectory(parent: Path, directory: Path): Path {
    require(directory.isAbsolute && directory.normalize() == directory && directory.parent == parent)
    require(directory.fileName.toString().startsWith("signature-runtime-"))
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    require(directory.toRealPath().parent == parent.toRealPath())
    return directory
}

private object NoProbeCredentials : CredentialStore {
    override fun read(key: String): ByteArray? = error("Credential access forbidden")
    override fun write(key: String, value: ByteArray) = error("Credential access forbidden")
    override fun delete(key: String) = error("Credential access forbidden")
}
