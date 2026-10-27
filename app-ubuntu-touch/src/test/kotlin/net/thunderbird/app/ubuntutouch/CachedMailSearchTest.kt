package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedMailSearchTest {
    @get:Rule val directory = TemporaryFolder()
    private val account = AccountIdFactory.create()
    private val summary = MailSummary("1", "Café plans", "Alice <alice@example.invalid>", true)

    @Test
    fun `Unicode literal AND terms span sender subject and decoded body without network`() {
        val testSubject = SearchFixture(listOf(MailContent(summary, "Budget λ 100%_", true)))
        assertThat(testSubject.search(account, "INBOX", "ALICE cafe\u0301 λ", {}).map { it.id })
            .isEqualTo(listOf("1"))
        assertThat(testSubject.search(account, "INBOX", "100%_", {}).map { it.id }).isEqualTo(listOf("1"))
        assertThat(testSubject.search(account, "INBOX", "alice missing", {})).isEqualTo(emptyList())
        assertThat(testSubject.search(account, "INBOX", "   ", {})).isEqualTo(listOf(summary))
    }

    @Test
    fun `HTML placeholder text is not searched and scan cooperatively cancels`() {
        val testSubject = SearchFixture(listOf(MailContent(summary, "HTML not available", true, plainText = false)))
        assertThat(testSubject.search(account, "INBOX", "HTML", {})).isEqualTo(emptyList())
        var checks = 0
        val failure = runCatching {
            testSubject.search(account, "INBOX", "not present") {
                checks++
                if (checks == 2) throw MailOperationCancelledException()
            }
        }.exceptionOrNull()
        assertThat(failure is MailOperationCancelledException).isTrue()
        assertThat(testSubject.bodyReads).isEqualTo(1)
        assertThat(runCatching { testSubject.search(account, "INBOX", "x".repeat(513), {}) }.isFailure).isTrue()
    }

    @Test
    fun `stored MIME search excludes attachment bodies isolates accounts folders and survives reopen`() = runTest {
        val previous = BinaryTempFileBody.getTempDirectory()
        BinaryTempFileBody.setTempDirectory(directory.root)
        try {
            val path = directory.root.toPath().resolve("search.sqlite")
            MailDatabase(path).use { database ->
                seedSearchCache(database)
            }
            MailDatabase(path).use { database ->
                val logger = DefaultLogger(object : LogSink {
                    override val level = LogLevel.VERBOSE
                    override fun log(event: LogEvent) = error("Search must not log mail")
                })
                val sessions = ForegroundMailSessionFactory(
                    UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore()),
                    database,
                    JvmTrustedSocketFactory(),
                    logger,
                    directory.root.toPath(),
                )
                val testSubject = PersistentLiveMailRepository(database, sessions)
                assertThat(
                    testSubject.search(account, "INBOX", "alice café λ", {
                    }).map { it.id },
                ).isEqualTo(listOf("1"))
                assertThat(testSubject.search(account, "INBOX", "Attachmentneedle", {})).isEqualTo(emptyList())
                assertThat(testSubject.search(account, "INBOX", "Foreignneedle", {})).isEqualTo(emptyList())
                assertThat(testSubject.search(account, "Other", "budget", {})).isEqualTo(emptyList())
                val hits = testSubject.searchAccount(account, "", {})
                assertThat(hits.map { it.message.id }).isEqualTo(listOf("1", "1"))
                assertThat(hits.map { it.folder.id }).isEqualTo(listOf("INBOX", "Other"))
                assertThat(testSubject.searchAccount(account, "Otherneedle", {}).single().folder.id).isEqualTo("Other")
                assertThat(testSubject.searchAccount(account, "Foreignneedle", {})).isEqualTo(emptyList())
                assertThat(hits.first().toString()).isEqualTo("CachedMailHit(redacted)")
            }
        } finally {
            BinaryTempFileBody.setTempDirectory(previous)
        }
    }

    private suspend fun seedSearchCache(database: MailDatabase) {
        val local = PersistentBackendStorage(database, account.toString())
        local.createFolderUpdater().use {
            it.createFolders(
                listOf(
                    FolderInfo("INBOX", "Inbox", FolderType.INBOX),
                    FolderInfo("Other", "Other", FolderType.REGULAR),
                ),
            )
        }
        val raw = "From: Alice <alice@example.invalid>\r\nSubject: Project café\r\n" +
            "Content-Type: multipart/mixed; boundary=fixture\r\n\r\n" +
            "--fixture\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nBudget λ\r\n" +
            "--fixture\r\nContent-Type: text/plain\r\n" +
            "Content-Disposition: attachment; filename=private.txt\r\n" +
            "\r\nAttachmentneedle\r\n--fixture--\r\n"
        val message = MimeMessage.parseMimeMessage(raw.byteInputStream(), true).apply { uid = "1" }
        local.getFolder("INBOX").saveMessage(message, MessageDownloadState.FULL)
        local.getFolder("Other").saveMessage(
            MimeMessage.parseMimeMessage("Subject: Other subject\r\n\r\nOtherneedle".byteInputStream(), true)
                .apply { uid = "1" },
            MessageDownloadState.FULL,
        )
        val other = PersistentBackendStorage(database, AccountIdFactory.create().toString())
        other.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
        }
        other.getFolder("INBOX").saveMessage(
            MimeMessage.parseMimeMessage(
                "Subject: Foreignneedle\r\n\r\nForeignneedle".byteInputStream(),
                true,
            ).apply { uid = "1" },
            MessageDownloadState.FULL,
        )
    }

    private inner class SearchFixture(private val cached: List<MailContent>) : LiveMailRepository {
        var bodyReads = 0
        override fun folders(account: AccountId) = listOf(MailFolder("INBOX", "Inbox"))
        override fun messages(account: AccountId, folder: String): List<MailSummary> {
            check(account == this@CachedMailSearchTest.account && folder == "INBOX")
            return cached.map { it.summary }
        }
        override fun content(account: AccountId, folder: String, uid: String): MailContent {
            bodyReads++
            check(account == this@CachedMailSearchTest.account && folder == "INBOX")
            return cached.single { it.summary.id == uid }
        }
        override fun refresh(account: AccountId, folder: String?) = error("Search must remain offline")
        override fun download(account: AccountId, folder: String, uid: String) = error("Search must remain offline")
    }
}
