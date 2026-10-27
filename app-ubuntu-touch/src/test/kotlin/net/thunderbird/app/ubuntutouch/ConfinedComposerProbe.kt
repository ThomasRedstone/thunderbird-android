package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory

/** Opt-in test executable. Uses only a newly created private fixture, never the application's real mail database. */
object ConfinedComposerProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 1)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args.single()} (enforce)") { "Expected enforcing application profile" }
        check(withContext(Dispatchers.Main) { SwingUtilities.isEventDispatchThread() })
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "composer-runtime-")
        try {
            checkFixture(directory)
        } finally {
            check(directory.toFile().deleteRecursively())
        }
        println("Enforcing profile, Swing Main dispatcher, SQLite draft/queue and uncertain recovery: PASS")
    }

    private fun checkFixture(directory: Path) {
        var logs = 0
        val logger = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) {
                logs++
            }
        })
        initializeMailRuntime(logger, directory.resolve("mime"))
        val settings = ServerSettings(
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
            AccountIdFactory.create(),
            "Controlled fixture",
            "fixture@example.invalid",
            settings,
            settings.copy(type = "smtp", port = 465),
            UUID.randomUUID().toString(),
        )
        val path = directory.resolve("mail.sqlite")
        MailDatabase(path).use { database ->
            AccountSettingsStorage(database).insert(account)
            SignonIdentityIndex(database).put("local-index-fixture", 42)
            database.execute("DROP TABLE outgoing")
            database.execute("PRAGMA user_version=3")
        }
        val id = MailDatabase(path).use { database ->
            check(database.query("PRAGMA user_version") { getLong(0) }.single() == 5L)
            check(AccountSettingsStorage(database).all().single().id == account.id)
            check(SignonIdentityIndex(database).find("local-index-fixture") == 42L)
            val store = OutgoingMailStore(database)
            val draft = store.create(account.id)
            store.save(account.id, draft.id, "recipient@example.invalid", "Phone fixture", "Controlled body λ")
            store.queue(account, draft.id)
            check(checkNotNull(store.claim(account.id, draft.id)).isNotEmpty())
            check(store.claim(account.id, draft.id) == null)
            draft.id
        }
        MailDatabase(path).use { database ->
            val store = OutgoingMailStore(database)
            check(store.get(account.id, id).state == OutgoingState.UNCERTAIN)
            check(store.get(account.id, id).hasUncertainAttempt)
            check(runCatching { store.retry(account.id, id) }.isFailure)
            store.retry(account.id, id, acknowledgeDuplicateRisk = true)
            check(store.get(account.id, id).state == OutgoingState.QUEUED)
            check(store.get(account.id, id).body == "Controlled body λ")
        }
        check(logs == 0)
    }
}
