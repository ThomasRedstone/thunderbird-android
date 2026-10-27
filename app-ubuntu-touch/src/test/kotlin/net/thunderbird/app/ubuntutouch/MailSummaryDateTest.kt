package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.util.UUID
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailSummaryDateTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `SQLite projection preserves effective date for list and reader without credentials or network`() {
        val previous = BinaryTempFileBody.getTempDirectory()
        try {
            MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                val raw = FakeCredentialStore()
                val manager = UtAccountManager(AccountSettingsStorage(database), raw)
                val id = AccountIdFactory.create()
                val server = serverSettings()
                manager.createWithPasswords(
                    UtMailAccount(
                        id,
                        "Controlled",
                        "fixture@example.invalid",
                        server,
                        server.copy(type = "smtp", port = 465),
                        UUID.randomUUID().toString(),
                    ),
                    AccountPasswords("incoming", "outgoing"),
                )
                database.execute(
                    "INSERT INTO folders(account,server_id,name,type) VALUES(?,?,?,?)",
                    id.toString(),
                    "INBOX",
                    "Inbox",
                    "INBOX",
                )
                val folder = database.query("SELECT id FROM folders") { getLong(0) }.single()
                database.execute(
                    "INSERT INTO messages(folder,uid,mime,effective_date) VALUES(?,?,?,?)",
                    folder,
                    "1",
                    "From: fixture@example.invalid\r\nSubject: Cached\r\n\r\nBody".toByteArray(),
                    1234567L,
                )
                raw.locked = true
                val logger = silentLogger()
                val testSubject = PersistentLiveMailRepository(
                    database,
                    ForegroundMailSessionFactory(
                        manager,
                        database,
                        JvmTrustedSocketFactory(),
                        logger,
                        directory.root.toPath().resolve("mime"),
                    ),
                )
                assertThat(testSubject.messages(id, "INBOX").single().effectiveDate).isEqualTo(1234567L)
                assertThat(testSubject.content(id, "INBOX", "1").summary.effectiveDate).isEqualTo(1234567L)
            }
        } finally {
            BinaryTempFileBody.setTempDirectory(previous)
        }
    }
    private fun serverSettings() = ServerSettings(
        "imap",
        "mail.example.invalid",
        993,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture@example.invalid",
        null,
        null,
    )

    private fun silentLogger() = DefaultLogger(object : LogSink {
        override val level = LogLevel.VERBOSE
        override fun log(event: LogEvent) = error("Cache read must not log mail data")
    })
}
