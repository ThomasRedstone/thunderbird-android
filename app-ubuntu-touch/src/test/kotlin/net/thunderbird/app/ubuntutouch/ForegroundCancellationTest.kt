package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ForegroundCancellationTest(private val incoming: Boolean, private val security: ConnectionSecurity) {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `closing session interrupts an active socket before authentication`() {
        val previousMime = BinaryTempFileBody.getTempDirectory()
        val executor = Executors.newSingleThreadExecutor()
        var logs = 0
        val logger = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) {
                logs++
            }
        })
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5000
                MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                    checkSession(database, server, logger, executor)
                }
            }
            assertThat(logs).isEqualTo(0)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
            BinaryTempFileBody.setTempDirectory(previousMime)
        }
    }

    private fun checkSession(database: MailDatabase, server: ServerSocket, logger: Logger, executor: ExecutorService) {
        val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
        val settings = ServerSettings(
            "imap",
            server.inetAddress.hostAddress,
            server.localPort,
            security,
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
            settings.copy(type = "smtp"),
            UUID.randomUUID().toString(),
        )
        manager.createWithPasswords(account, AccountPasswords("fixture", "fixture"))
        val factory = ForegroundMailSessionFactory(
            manager,
            database,
            JvmTrustedSocketFactory(),
            logger,
            directory.root.toPath().resolve("mime"),
        )
        factory.open(account.id).use { testSubject ->
            val operation = executor.submit<Throwable?> {
                runCatching {
                    if (incoming) testSubject.checkSettings() else testSubject.send(message())
                }.exceptionOrNull()
            }
            server.accept().use { peer ->
                // Ensure direct TLS is actually waiting for ServerHello, rather than only testing TCP close.
                if (security == ConnectionSecurity.SSL_TLS_REQUIRED) {
                    peer.soTimeout = 5000
                    check(peer.getInputStream().read() >= 0)
                }
                testSubject.close()
                val failure = operation.get(3, TimeUnit.SECONDS)
                assertThat(failure != null).isTrue()
                if (!incoming) {
                    check(failure is MessagingException && !failure.isPermanentFailure) {
                        "Cancellation before SMTP DATA must not be classified as possible acceptance"
                    }
                }
            }
        }
    }

    private fun message() = MimeMessage.parseMimeMessage(
        (
            "From: fixture@example.invalid\r\nTo: recipient@example.invalid\r\n" +
                "Subject: Cancel fixture\r\nContent-Type: text/plain\r\n\r\nBody\r\n"
            ).byteInputStream(),
        true,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "incoming={0}, security={1}")
        fun cases() = listOf(
            arrayOf<Any>(true, ConnectionSecurity.SSL_TLS_REQUIRED),
            arrayOf<Any>(true, ConnectionSecurity.STARTTLS_REQUIRED),
            arrayOf<Any>(false, ConnectionSecurity.SSL_TLS_REQUIRED),
            arrayOf<Any>(false, ConnectionSecurity.STARTTLS_REQUIRED),
        )
    }
}
