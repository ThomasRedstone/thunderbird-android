package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory

/** Opt-in synthetic STARTTLS test; production trust, database and accounts are never changed. */
object ConfinedMoveProtocolProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5)
        val profile = "mail-demo.thomasredstone_mail-demo_0.1.32"
        check(args[0] == profile)
        check(Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000') == "$profile (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        val directory = Files.createTempDirectory(parent, "move-protocol-")
        val server = ServerSettings(
            "imap",
            args[1],
            args[2].toInt(),
            ConnectionSecurity.STARTTLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        val account = UtMailAccount(
            AccountIdFactory.create(),
            "Move fixture",
            "fixture@example.invalid",
            server,
            server.copy(type = "smtp", port = args[3].toInt()),
            UUID.randomUUID().toString(),
        )
        try {
            checkMove(directory, account, Path.of(args[4]))
        } finally {
            MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                val accounts = manager(database)
                if (accounts.findById(account.id) != null) accounts.delete(account.id)
                check(accounts.cleanupUnusedCredentials()) { "Keep fixture index for credential cleanup" }
            }
        }
        check(directory.toFile().deleteRecursively())
        println("Enforcing STARTTLS Move, destination reconciliation and verified payload release: PASS")
    }

    private fun checkMove(directory: Path, account: UtMailAccount, certificate: Path) {
        var logs = 0
        val logger = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) {
                logs++
            }
        })
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            Files.newInputStream(certificate).use {
                setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(it))
            }
        }
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val accounts = manager(database)
            accounts.createWithPasswords(account, AccountPasswords(PASSWORD, PASSWORD))
            val sessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(trust)),
                logger,
                directory.resolve("mime"),
            )
            sessions.open(account.id).use {
                it.refreshFolders()
                it.sync("INBOX")
                it.sync("Archive")
            }
            val repository = PersistentLiveMailRepository(database, sessions)
            val uid = repository.messages(account.id, "INBOX").single().id
            val operations = PersistentMailMoveOperations(database, accounts, sessions)
            val key = MailMessageKey(account.id, "INBOX", uid)
            operations.verify(key, MailOperation())
            val queued = operations.enqueue(key, "Archive")
            check(queued.state == MailMoveState.QUEUED)
            val complete = operations.submit(queued, MailOperation(), acknowledgeDuplicateRisk = false)
            check(complete.state == MailMoveState.COMPLETE)
            val payloads = MailMovePayloadStore(database)
            check(payloads.info(account.id, complete.id) != null)
            check(operations.reconcile(complete, MailOperation()))
            check(repository.messages(account.id, "INBOX").isEmpty())
            val destination = repository.messages(account.id, "Archive").single()
            check(repository.content(account.id, "Archive", destination.id).body.trim() == "Controlled incoming body")
            check(payloads.info(account.id, complete.id) == null)
            check(MailMoveStore(database).get(account.id, complete.id).state == MailMoveState.COMPLETE)
            check(runCatching { operations.submit(complete, MailOperation(), false) }.isFailure)
        }
        check(logs == 0)
    }

    private fun manager(database: MailDatabase) = UtAccountManager(
        AccountSettingsStorage(database),
        SignonCredentialStore(SignonIdentityIndex(database)),
    )

    private const val PASSWORD = "device-fixture-password"
}
