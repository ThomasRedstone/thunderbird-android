package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        println(
            "Confined Swing Main, draft/queue, lifecycle flush and uncertain recovery: PASS",
        )
    }

    private suspend fun checkFixture(directory: Path) {
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
            if ("in_reply_to" in database.query("PRAGMA table_info(outgoing)") { getText(1) }) {
                database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
                database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            }
            database.execute("DROP TABLE pending_flags")
            database.execute("PRAGMA user_version=3")
        }
        val id = MailDatabase(path).use { database ->
            check(database.query("PRAGMA user_version") { getLong(0) }.single() == 8L)
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
        checkLifecycle(path, account)
        check(logs == 0)
    }

    private suspend fun checkLifecycle(path: Path, account: UtMailAccount) {
        MailDatabase(path).use { database ->
            val store = OutgoingMailStore(database)
            val manager =
                UtAccountManager(AccountSettingsStorage(database), SignonCredentialStore(SignonIdentityIndex(database)))
            val model = withContext(Dispatchers.Main) {
                ComposerViewModel(manager, store, OutgoingMailSender(store) { _, _ -> error("Must not send") })
            }
            try {
                withContext(Dispatchers.Main) {
                    model.event(ComposerEvent.Account(account.id))
                    model.state.first { !it.busy }
                    model.event(ComposerEvent.New)
                    model.state.first { !it.busy }
                    model.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Immediate native close")))
                    model.event(ComposerEvent.Change(ComposeField.CC, TextFieldValue("copy@example.invalid")))
                    model.event(
                        ComposerEvent.Keyboard(ComposeField.BCC, MaliitTextEdit.Commit("blind@example.invalid")),
                    )
                    check(model.flushForClose())
                    model.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Invalid\r\nheader")))
                    check(!model.flushForClose())
                    check(model.state.value.dirty)
                    model.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Native background save")))
                    model.flushOnBackground()
                    model.state.first { !it.busy }
                }
                val selected = checkNotNull(model.state.value.selected)
                check(store.get(account.id, selected.id).subject == "Native background save")
                check(store.get(account.id, selected.id).cc == "copy@example.invalid")
                check(store.get(account.id, selected.id).bcc == "blind@example.invalid")
                check(!model.state.value.dirty)
                checkResponseModel(model, store, account)
            } finally {
                withContext(Dispatchers.Main) { model.close() }
            }
        }
    }
    private suspend fun checkResponseModel(model: ComposerViewModel, store: OutgoingMailStore, account: UtMailAccount) {
        val draft = store.create(
            account.id,
            ResponseSeed(
                to = "recipient@example.invalid",
                subject = "Re: Native",
                body = "\n\n> Original",
                inReplyTo = "<parent@example.invalid>",
                references = "<parent@example.invalid>",
            ),
        )
        withContext(Dispatchers.Main) {
            model.event(ComposerEvent.Resume(ComposeRequest(account.id, draft.id)))
            model.state.first { !it.busy }
            check(model.state.value.selected?.id == draft.id)
            model.event(ComposerEvent.Keyboard(ComposeField.BODY, MaliitTextEdit.Commit("Reply λ\n")))
            check(model.flushForClose())
        }
        check(store.get(account.id, draft.id).inReplyTo == "<parent@example.invalid>")
        check(store.get(account.id, draft.id).references == "<parent@example.invalid>")
        check(store.get(account.id, draft.id).body.startsWith("Reply λ"))
    }
}
