package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MimeMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountIdFactory

/** Opt-in installed-runtime probe; all accounts/mail live only in its own private disposable fixture. */
object ConfinedSearchProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 1)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args.single()} (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "search-runtime-")
        try {
            SearchRuntimeFixture.check(directory)
        } finally {
            check(directory.toFile().deleteRecursively())
        }
        println("Confined unified cached search, account-safe reader/flags/reply and Back: PASS")
    }
}

internal object SearchRuntimeFixture {
    private val noSecrets = object : CredentialStore {
        override fun read(key: String): ByteArray? = error("Offline search must not read secrets")
        override fun write(key: String, value: ByteArray) = error("Offline search must not write secrets")
        override fun delete(key: String) = error("Offline search must not delete secrets")
    }
    private val logger = DefaultLogger(object : LogSink {
        override val level = LogLevel.VERBOSE
        override fun log(event: LogEvent) = error("Offline search must not log mail")
    })

    suspend fun check(directory: Path) {
        initializeMailRuntime(logger, directory.resolve("mime"))
        val path = directory.resolve("mail.sqlite")
        val primary = account("Primary")
        val secondary = account("Secondary")
        MailDatabase(path).use { database -> seed(database, listOf(primary, secondary)) }
        val before = bodyFiles(directory.resolve("mime"))
        MailDatabase(path).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), noSecrets)
            val sessions =
                ForegroundMailSessionFactory(
                    manager,
                    database,
                    JvmTrustedSocketFactory(),
                    logger,
                    directory.resolve("mime"),
                )
            val repository = PersistentLiveMailRepository(database, sessions)
            val model = withContext(Dispatchers.Main) { LiveMailViewModel(manager, repository) }
            try {
                withContext(Dispatchers.Main) { navigate(model, primary, secondary) }
                check(PendingFlagStore(database).list(primary.id).isEmpty())
                check(PendingFlagStore(database).list(secondary.id).single().folder == "INBOX")
                check(OutgoingMailStore(database).list(primary.id).isEmpty())
                check(OutgoingMailStore(database).list(secondary.id).single().state == OutgoingState.DRAFT)
                val attachments = CachedAttachmentStore(database, directory.resolve("exports"))
                val info = attachments.list(secondary.id, "INBOX", "1").single()
                attachments.prepare(secondary.id, "INBOX", "1", info.reference).use {
                    check(Files.readAllBytes(it.file).contentEquals(byteArrayOf(0, 1, 2, 3, 4, 5)))
                }
                Files.list(directory.resolve("exports")).use { check(it.count() == 0L) }
            } finally {
                withContext(Dispatchers.Main) { model.close() }
            }
        }
        check(bodyFiles(directory.resolve("mime")) == before) { "Cached MIME resources were not released" }
    }

    private fun bodyFiles(directory: Path): Set<Path> = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().startsWith("body") }.toList().toSet()
    }

    private suspend fun navigate(model: LiveMailViewModel, primary: UtMailAccount, secondary: UtMailAccount) {
        model.event(LiveMailEvent.OpenAccount(primary.id))
        model.state.first { !it.busy }
        model.event(LiveMailEvent.OpenFolder(model.state.value.folders.single()))
        model.state.first { !it.busy }
        model.event(LiveMailEvent.SearchScope(CachedSearchScope.ALL_ACCOUNTS))
        model.state.first { !it.busy }
        val hits = model.state.value.searchHits
        check(hits.size == 2 && hits.all { it.message.id == "1" && it.folder.id == "INBOX" })
        model.event(LiveMailEvent.OpenSearchResult(hits.single { it.account == secondary.id }))
        model.state.first { !it.busy }
        check(model.state.value.notice == null)
        check(model.state.value.content?.body?.trim() == "Body Secondary λ")
        model.event(LiveMailEvent.ChangeFlag(Flag.FLAGGED, true))
        check(model.flushForClose())
        model.event(LiveMailEvent.Respond(ResponseMode.REPLY))
        check(model.flushForClose())
        check(model.state.value.composeRequest?.account == secondary.id)
        model.event(LiveMailEvent.Back)
        model.state.first { !it.busy }
        check(model.state.value.account == primary.id)
        check(model.state.value.searchScope == CachedSearchScope.ALL_ACCOUNTS)
        check(model.state.value.searchHits.size == 2 && model.state.value.notice == null)
    }

    private suspend fun seed(database: MailDatabase, accounts: List<UtMailAccount>) {
        for (account in accounts) {
            AccountSettingsStorage(database).insert(account)
            val storage = PersistentBackendStorage(database, account.id.toString())
            storage.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            val folder = storage.getFolder("INBOX")
            folder.setFolderExtraNumber("imapUidValidity", 42)
            val text = Base64.getEncoder().encodeToString("Body ${account.displayName} λ".toByteArray())
            val raw = "From: sender@example.invalid\r\nSubject: Shared subject\r\n" +
                "Message-ID: <${account.id}@example.invalid>\r\n" +
                "Content-Type: multipart/mixed; boundary=fixture\r\n\r\n" +
                "--fixture\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n$text\r\n" +
                "--fixture\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=fixture.bin\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAAECAwQF\r\n--fixture--\r\n"
            val message = MimeMessage.parseMimeMessage(raw.byteInputStream(), true).apply { uid = "1" }
            folder.saveMessage(message, MessageDownloadState.FULL)
        }
    }

    private fun account(name: String): UtMailAccount {
        val id = AccountIdFactory.create()
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
        return UtMailAccount(id, name, "fixture@example.invalid", settings, settings.copy(type = "smtp"), id.toString())
    }
}
