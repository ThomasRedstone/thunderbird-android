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
        require(args.size in 5..6)
        val mode = args.getOrNull(5)
        require(mode == null || mode in setOf("unknown", "archive", "trash", "copy", "copy-unknown"))
        val unknown = mode == "unknown" || mode == "copy-unknown"
        val kind = if (mode?.startsWith("copy") == true) MailMoveKind.COPY else MailMoveKind.MOVE
        val role = when (mode) {
            "archive" -> MailFolderRole.ARCHIVE
            "trash" -> MailFolderRole.TRASH
            else -> null
        }
        val profile = "mail-demo.thomasredstone_mail-demo_0.1.37"
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
            checkMove(directory, account, Path.of(args[4]), unknown, role, kind)
        } finally {
            MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                val accounts = manager(database)
                if (accounts.findById(account.id) != null) accounts.delete(account.id)
                check(accounts.cleanupUnusedCredentials()) { "Keep fixture index for credential cleanup" }
            }
        }
        check(directory.toFile().deleteRecursively())
        println("Enforcing STARTTLS filing, destination reconciliation and retained-payload policy: PASS")
    }

    private fun checkMove(
        directory: Path,
        account: UtMailAccount,
        certificate: Path,
        unknown: Boolean,
        role: MailFolderRole?,
        kind: MailMoveKind,
    ) {
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
            exerciseFiling(database, accounts, sessions, unknown, role, kind)
        }
        check(logs == 0)
    }

    private fun exerciseFiling(
        database: MailDatabase,
        accounts: UtAccountManager,
        sessions: ForegroundMailSessionFactory,
        unknown: Boolean,
        role: MailFolderRole?,
        kind: MailMoveKind,
    ) {
        val account = accounts.findAll().single()
        sessions.open(account.id).use {
            it.refreshFolders()
            it.sync("INBOX")
            it.sync("Archive")
        }
        val repository = PersistentLiveMailRepository(database, sessions)
        val original = repository.messages(account.id, "INBOX").single()
        val uid = original.id
        val operations = PersistentMailMoveOperations(database, accounts, sessions)
        val key = MailMessageKey(account.id, "INBOX", uid)
        operations.verify(key, MailOperation())
        if (role != null) {
            operations.configureRole(key, role, "Archive")
            check(operations.overview(key).roles[role] == "Archive")
        }
        val queued = operations.enqueue(key, "Archive", role, kind)
        check(queued.state == MailMoveState.QUEUED)
        val complete = operations.submit(queued, MailOperation(), acknowledgeDuplicateRisk = false)
        val expected = if (unknown) MailMoveState.UNKNOWN else MailMoveState.COMPLETE
        check(complete.state == expected)
        val payloads = MailMovePayloadStore(database)
        check(payloads.info(account.id, complete.id) != null)
        check(operations.reconcile(complete, MailOperation()))
        if (kind == MailMoveKind.COPY) {
            checkCopySource(original, repository, operations, key, unknown)
        } else if (!unknown) {
            check(repository.messages(account.id, "INBOX").isEmpty())
        }
        val destination = repository.messages(account.id, "Archive").single()
        check(repository.content(account.id, "Archive", destination.id).body.trim() == "Controlled incoming body")
        check((payloads.info(account.id, complete.id) != null) == unknown)
        check(MailMoveStore(database).get(account.id, complete.id).state == expected)
        check(runCatching { operations.submit(complete, MailOperation(), false) }.isFailure)
    }

    private fun checkCopySource(
        original: MailSummary,
        repository: PersistentLiveMailRepository,
        operations: PersistentMailMoveOperations,
        key: MailMessageKey,
        unknown: Boolean,
    ) {
        val source = repository.messages(key.account, key.folder).single()
        check(source.id == key.uid && source.unread == original.unread && source.starred == original.starred)
        check(repository.content(key.account, key.folder, key.uid).body.trim() == "Controlled incoming body")
        if (!unknown) {
            check(runCatching { operations.enqueue(key, "Archive", kind = MailMoveKind.COPY) }.isFailure)
            val repeated = operations.enqueue(
                key,
                "Archive",
                kind = MailMoveKind.COPY,
                acknowledgeDuplicateRisk = true,
            )
            check(repeated.kind == MailMoveKind.COPY)
            operations.cancelQueued(repeated)
        }
    }

    private fun manager(database: MailDatabase) = UtAccountManager(
        AccountSettingsStorage(database),
        SignonCredentialStore(SignonIdentityIndex(database)),
    )

    private const val PASSWORD = "device-fixture-password"
}
