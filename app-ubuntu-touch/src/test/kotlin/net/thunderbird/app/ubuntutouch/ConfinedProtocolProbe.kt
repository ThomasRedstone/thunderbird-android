package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.CertificateValidationException
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
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory

/** Opt-in device integration probe. Fixture trust is injected only here; production trust is never modified. */
object ConfinedProtocolProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in setOf(2, 5, 6, 7))
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args[0]} (enforce)")
        if (args.size == 2) verifyRestart(Path.of(args[1])) else prepare(args)
    }

    private fun parent() = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")

    private fun prepare(args: Array<String>) {
        val retain = args.size >= 6
        if (retain) require(args[5] == "retain-for-restart")
        val security = if (args.size == 7) {
            require(args[6] == "starttls")
            ConnectionSecurity.STARTTLS_REQUIRED
        } else {
            ConnectionSecurity.SSL_TLS_REQUIRED
        }
        Files.createDirectories(parent())
        val directory = Files.createTempDirectory(parent(), "protocol-runtime-")
        val account = UtMailAccount(
            AccountIdFactory.create(),
            "Controlled device mailbox",
            "fixture@example.invalid",
            settings("imap", args[1], args[2].toInt(), security),
            settings("smtp", args[1], args[3].toInt(), security),
            UUID.randomUUID().toString(),
        )
        var prepared = false
        try {
            checkNetwork(directory, account, Path.of(args[4]))
            if (retain) Files.writeString(directory.resolve("protocol.fixture"), "controlled-device-protocol-v1")
            prepared = true
        } finally {
            if (!retain || !prepared) cleanup(directory, account.id)
        }
        if (retain) println("PREPARED_FIXTURE=$directory")
        println("Enforcing profile, protected account, TLS IMAP/cache/reopen and guarded SMTP acceptance: PASS")
    }

    private fun verifyRestart(requested: Path) {
        val directory = requested.toAbsolutePath().normalize()
        require(directory.parent == parent() && directory.fileName.toString().startsWith("protocol-runtime-"))
        check(Files.readString(directory.resolve("protocol.fixture")) == "controlled-device-protocol-v1")
        val id = MailDatabase(directory.resolve("mail.sqlite")).use { manager(it).findAll().single().id }
        try {
            var logs = 0
            val logger = DefaultLogger(object : LogSink {
                override val level = LogLevel.VERBOSE
                override fun log(event: LogEvent) {
                    logs++
                }
            })
            MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                val manager = manager(database)
                val passwords = manager.passwords(id)
                check(passwords.incoming == PASSWORD && passwords.outgoing == PASSWORD)
                val sessions = ForegroundMailSessionFactory(
                    manager,
                    database,
                    JvmTrustedSocketFactory(),
                    logger,
                    directory.resolve("mime"),
                )
                val repository = PersistentLiveMailRepository(database, sessions)
                val message = repository.messages(id, "INBOX").single()
                check(repository.content(id, "INBOX", message.id).body.trim() == "Controlled incoming body")
                check(OutgoingMailStore(database).list(id).single().state == OutgoingState.SENT)
            }
            check(logs == 0)
        } finally {
            cleanup(directory, id)
        }
        println("Fresh JVM protected credentials, cached body and outbox reopen with test servers stopped: PASS")
    }

    private fun cleanup(directory: Path, id: AccountId) {
        // Retain the private index if cleanup fails; never lose a created protected identity's reference.
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            if (manager.findById(id) != null) manager.delete(id)
            check(manager.cleanupUnusedCredentials()) { "Fixture credential cleanup pending; retain index" }
        }
        check(directory.toFile().deleteRecursively())
    }

    private fun checkNetwork(directory: Path, account: UtMailAccount, certificate: Path) {
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
        val path = directory.resolve("mail.sqlite")
        val mime = directory.resolve("mime")
        MailDatabase(path).use { database ->
            val manager = manager(database)
            manager.createWithPasswords(account, AccountPasswords(PASSWORD, PASSWORD))
            val untrusted = ForegroundMailSessionFactory(manager, database, JvmTrustedSocketFactory(), logger, mime)
            val rejection = runCatching { untrusted.open(account.id).use { it.checkSettings() } }.exceptionOrNull()
            check(rejection is CertificateValidationException) { "Unknown fixture certificate must be rejected" }
            val sessions = ForegroundMailSessionFactory(
                manager,
                database,
                JvmTrustedSocketFactory(systemTrustManager(trust)),
                logger,
                mime,
            )
            sessions.open(account.id).use {
                it.checkSettings()
                it.refreshFolders()
                it.sync("INBOX")
            }
            val repository = PersistentLiveMailRepository(database, sessions)
            val message = repository.messages(account.id, "INBOX").single()
            check(message.subject == "Device controlled inbox")
            check(repository.content(account.id, "INBOX", message.id).body.trim() == "Controlled incoming body")
            val outgoing = OutgoingMailStore(database)
            val draft = outgoing.create(account.id)
            outgoing.save(
                account.id,
                draft.id,
                "recipient@example.invalid",
                "Device queued outgoing",
                "Phone body λ\r\n.A leading dot",
            )
            outgoing.queue(account, draft.id)
            val sender = OutgoingMailSender(outgoing, SessionOutgoingTransport(sessions))
            check(sender.send(account.id, draft.id) == OutgoingState.SENT)
            check(sender.send(account.id, draft.id) == OutgoingState.SENT)
        }
        MailDatabase(path).use { database ->
            val manager = manager(database)
            check(manager.passwords(account.id).incoming == PASSWORD)
            val sessions = ForegroundMailSessionFactory(manager, database, JvmTrustedSocketFactory(), logger, mime)
            val repository = PersistentLiveMailRepository(database, sessions)
            val message = repository.messages(account.id, "INBOX").single()
            check(repository.content(account.id, "INBOX", message.id).body.trim() == "Controlled incoming body")
            check(OutgoingMailStore(database).list(account.id).single().state == OutgoingState.SENT)
        }
        check(logs == 0)
    }

    private fun manager(database: MailDatabase) = UtAccountManager(
        AccountSettingsStorage(database),
        SignonCredentialStore(SignonIdentityIndex(database)),
    )

    private fun settings(type: String, host: String, port: Int, security: ConnectionSecurity) = ServerSettings(
        type,
        host,
        port,
        security,
        AuthType.PLAIN,
        "fixture",
        null,
        null,
    )

    private const val PASSWORD = "device-fixture-password"
}
