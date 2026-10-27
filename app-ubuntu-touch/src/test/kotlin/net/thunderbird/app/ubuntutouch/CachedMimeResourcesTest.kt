package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeUtility
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedMimeResourcesTest {
    @get:Rule val directory = TemporaryFolder()
    private var previous: java.io.File? = null
    private val logger = DefaultLogger(object : LogSink {
        override val level = LogLevel.VERBOSE
        override fun log(event: LogEvent) = error("Cached mail must not log content")
    })

    @Before
    fun setup() {
        previous = BinaryTempFileBody.getTempDirectory()
        initializeMailRuntime(logger, directory.root.toPath())
    }

    @After
    fun cleanup() {
        BinaryTempFileBody.setTempDirectory(previous)
    }

    @Test
    fun `repeated cached summaries body search and replies release parsed body files`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = seed(database)
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val sessions = ForegroundMailSessionFactory(
                manager,
                database,
                JvmTrustedSocketFactory(),
                logger,
                directory.root.toPath(),
            )
            val testSubject = PersistentLiveMailRepository(database, sessions)
            val before = bodyFiles()
            repeat(3) {
                assertThat(testSubject.messages(account.id, "INBOX").single().subject).isEqualTo("Resource fixture")
                assertThat(testSubject.content(account.id, "INBOX", "1").body.trim()).isEqualTo("Body λ")
                assertThat(testSubject.searchAccount(account.id, "body", {}).size).isEqualTo(1)
                testSubject.respond(account.id, "INBOX", "1", ResponseMode.REPLY)
            }
            assertThat(bodyFiles()).isEqualTo(before)
            assertThat(OutgoingMailStore(database).list(account.id).size).isEqualTo(3)
        }
    }

    @Test
    fun `projection exception and cancellation release nested message temporary bodies`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = seed(database, nested = true)
            val cached = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
            val before = bodyFiles()
            listOf(
                IllegalStateException("Controlled failure"),
                CancellationException("Controlled cancellation"),
            ).forEach {
                val failure = it
                val caught = runCatching {
                    cached.withCachedMessage("1") {
                        assertThat(bodyFiles().size > before.size).isTrue()
                        throw failure
                    }
                }.exceptionOrNull()
                assertThat(caught === failure).isTrue()
                assertThat(bodyFiles()).isEqualTo(before)
            }
        }
    }

    @Test
    fun `owned projection does not dispose a separate caller-owned MIME parse`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = seed(database)
            val cached = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
            val external = checkNotNull(cached.readMessage("1"))
            val externalFiles = bodyFiles()
            assertThat(externalFiles.isNotEmpty()).isTrue()
            assertThat(cached.withCachedMessage("1") { plainTextBody(it)?.trim() }).isEqualTo("Body λ")
            assertThat(bodyFiles()).isEqualTo(externalFiles)
            assertThat(plainTextBody(external)?.trim()).isEqualTo("Body λ")
            val attachments = mutableListOf<com.fsck.k9.mail.Part>()
            MessageExtractor.findViewablesAndAttachments(external, null, attachments)
            val bytes = MimeUtility.decodeBody(attachments.single().body).use { it.readBytes() }
            assertThat(bytes.contentEquals(byteArrayOf(0, 1, 2, 3, 4, 5))).isTrue()
        }
    }

    @Test
    fun `cleanup failure retains primary exception and still cleans other owned files`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = seed(database)
            val cached = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
            val primary = IllegalArgumentException("Controlled projection failure")
            var blocked: java.io.File? = null
            val caught = runCatching {
                cached.withCachedMessage("1") { message ->
                    val body = (message.body as Multipart).getBodyPart(1).body as BinaryTempFileBody
                    blocked = body.file
                    Files.delete(body.file.toPath())
                    Files.createDirectory(body.file.toPath())
                    Files.writeString(body.file.toPath().resolve("retained"), "Controlled fixture")
                    throw primary
                }
            }.exceptionOrNull()
            assertThat(caught === primary).isTrue()
            assertThat(primary.suppressed.single().message).isEqualTo("Unable to release cached message resources")
            assertThat(bodyFiles()).isEqualTo(setOf(checkNotNull(blocked)))
        }
    }

    private fun bodyFiles() = directory.root.listFiles().orEmpty().filter { it.name.startsWith("body") }.toSet()

    private suspend fun seed(database: MailDatabase, nested: Boolean = false): UtMailAccount {
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
        val account = UtMailAccount(
            id,
            "Fixture",
            "self@example.invalid",
            settings,
            settings.copy(type = "smtp"),
            id.toString(),
        )
        AccountSettingsStorage(database).insert(account)
        val storage = PersistentBackendStorage(database, id.toString())
        storage.createFolderUpdater().use { it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX))) }
        val content = "From: sender@example.invalid\r\nSubject: Resource fixture\r\n" +
            "Content-Type: multipart/mixed; boundary=fixture\r\n\r\n" +
            "--fixture\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\nQm9keSDOuw==\r\n" +
            "--fixture\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename=fixture.bin\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\nAAECAwQF\r\n--fixture--\r\n"
        val raw = if (nested) {
            "Content-Type: multipart/mixed; boundary=outer\r\n\r\n" +
                "--outer\r\nContent-Type: message/rfc822\r\n\r\n$content\r\n--outer--\r\n"
        } else {
            content
        }
        val message = MimeMessage.parseMimeMessage(raw.byteInputStream(), true).apply { uid = "1" }
        storage.getFolder("INBOX").saveMessage(message, MessageDownloadState.FULL)
        return account
    }
}
