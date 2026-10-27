package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeUtility
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import java.util.Properties
import java.util.UUID
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartTlsIntegrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `required STARTTLS upgrades before authentication and supports receiving and sending`() {
        val previous = BinaryTempFileBody.getTempDirectory()
        val server = GreenMail(
            arrayOf(
                ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
                ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP),
            ),
        )
        server.start()
        server.setUser("fixture@example.invalid", "fixture", "fixture")
        server.setUser("recipient@example.invalid", "recipient", "fixture")
        val incoming = jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(Properties()),
            (
                "From: sender@example.invalid\r\nTo: fixture@example.invalid\r\n" +
                    "Subject: Received over STARTTLS\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nIncoming body λ\r\n"
                )
                .byteInputStream(),
        )
        server.userManager.getUser("fixture").deliver(incoming)
        try {
            val tls = StartTlsMaterial(directory.root.toPath())
            StartTlsGateway(true, server.imap.port, tls.context).use { imap ->
                StartTlsGateway(false, server.smtp.port, tls.context).use { smtp ->
                    checkMail(server, tls, imap, smtp)
                }
            }
        } finally {
            server.stop()
            BinaryTempFileBody.setTempDirectory(previous)
        }
    }

    private fun checkMail(server: GreenMail, tls: StartTlsMaterial, imap: StartTlsGateway, smtp: StartTlsGateway) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val settings = ServerSettings(
                "imap",
                "localhost",
                imap.port,
                ConnectionSecurity.STARTTLS_REQUIRED,
                AuthType.PLAIN,
                "fixture",
                null,
                null,
            )
            val account = UtMailAccount(
                AccountIdFactory.create(),
                "Fixture",
                "fixture@example.invalid",
                settings,
                settings.copy(type = "smtp", port = smtp.port),
                UUID.randomUUID().toString(),
            )
            manager.createWithPasswords(account, AccountPasswords("fixture", "fixture"))
            val logger = DefaultLogger(object : LogSink {
                override val level = LogLevel.VERBOSE
                override fun log(event: LogEvent) = error("Mail must not be logged")
            })
            val testSubject = ForegroundMailSessionFactory(
                manager,
                database,
                JvmTrustedSocketFactory(systemTrustManager(tls.trust)),
                logger,
                directory.root.toPath().resolve("mime"),
            )
            testSubject.open(account.id).use { session ->
                session.checkSettings()
                session.send(message())
                assertThat(server.waitForIncomingEmail(5000, 2)).isTrue()
                session.refreshFolders()
                session.sync("INBOX")
            }
            val cached = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
            val received = checkNotNull(cached.readMessage(cached.getMessageServerIds().single()))
            assertThat(received.subject).isEqualTo("Received over STARTTLS")
            val body = MimeUtility.findFirstPartByMimeType(received, "text/plain")
            assertThat(MessageExtractor.getTextFromPart(body).trim()).isEqualTo("Incoming body λ")
            val sent = server.receivedMessages.single { it.subject == "STARTTLS fixture" }
            assertThat(sent.subject).isEqualTo("STARTTLS fixture")
            assertThat(sent.content.toString().trim()).isEqualTo("Controlled body")
            assertThat(sent.allRecipients.single().toString())
                .isEqualTo("recipient@example.invalid")
            assertThat(imap.upgrades.get() >= 2).isTrue()
            assertThat(smtp.upgrades.get() >= 2).isTrue()
        }
    }

    private fun message() = MimeMessage.parseMimeMessage(
        (
            "From: fixture@example.invalid\r\nTo: recipient@example.invalid\r\n" +
                "Subject: STARTTLS fixture\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nControlled body\r\n"
            ).byteInputStream(),
        true,
    )
}
