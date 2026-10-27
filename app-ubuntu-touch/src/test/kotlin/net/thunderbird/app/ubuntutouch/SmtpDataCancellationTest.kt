package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SmtpDataCancellationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `cancel after DATA without final acknowledgement persists uncertain and cannot blindly resend`() = runTest {
        val previousMime = BinaryTempFileBody.getTempDirectory()
        try {
            DataStallServer().use { server ->
                MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                    checkCancellation(database, server, this)
                }
            }
        } finally {
            BinaryTempFileBody.setTempDirectory(previousMime)
        }
    }

    private fun privacyLogger() = DefaultLogger(object : LogSink {
        override val level = LogLevel.VERBOSE
        override fun log(event: LogEvent) = error("Mail content must not be logged")
    })

    private suspend fun checkCancellation(database: MailDatabase, server: DataStallServer, scope: TestScope) {
        val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
        val settings = ServerSettings(
            "imap",
            "localhost",
            server.port,
            ConnectionSecurity.SSL_TLS_REQUIRED,
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
            settings.copy(type = "smtp"),
            UUID.randomUUID().toString(),
        )
        manager.createWithPasswords(account, AccountPasswords("fixture", "fixture"))
        val sessions = ForegroundMailSessionFactory(
            manager,
            database,
            JvmTrustedSocketFactory(systemTrustManager(server.trust)),
            privacyLogger(),
            directory.root.toPath().resolve("mime"),
        )
        val store = OutgoingMailStore(database)
        val draft = store.create(account.id)
        store.save(account.id, draft.id, "recipient@example.invalid", "Cancellation fixture", "Controlled body")
        store.queue(account, draft.id)
        val testSubject = ComposerViewModel(
            manager,
            store,
            OutgoingMailSender(store, SessionOutgoingTransport(sessions)),
            scope.backgroundScope,
        )
        testSubject.event(ComposerEvent.Account(account.id))
        testSubject.state.first { !it.busy }
        testSubject.event(ComposerEvent.Open(draft.id))
        testSubject.state.first { !it.busy }
        testSubject.event(ComposerEvent.Send)
        scope.runCurrent()
        check(server.receivedData.await(10, TimeUnit.SECONDS)) { "SMTP did not reach the DATA terminator" }
        val cancelledAt = System.nanoTime()
        testSubject.event(ComposerEvent.Cancel)
        assertThat(testSubject.state.value.busy).isTrue()
        testSubject.state.first { !it.busy }
        check(System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(3)) {
            "Cancellation did not interrupt the wait"
        }
        assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.UNCERTAIN)
        assertThat(testSubject.state.value.selected?.hasUncertainAttempt).isEqualTo(true)
        assertThat(testSubject.state.value.canCancel).isFalse()
        testSubject.event(ComposerEvent.Send)
        testSubject.state.first { !it.busy }
        assertThat(store.get(account.id, draft.id).state).isEqualTo(OutgoingState.UNCERTAIN)
    }
}

/** A real TLS/SMTP peer that reads DATA but withholds its final response. It does not forward or log mail. */
private class DataStallServer : AutoCloseable {
    private val material = Files.createTempDirectory("smtp-cancel-tls-")
    private val release = CountDownLatch(1)
    val receivedData = CountDownLatch(1)
    val trust: KeyStore
    private val server: SSLServerSocket
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var peer: Socket? = null
    val port: Int get() = server.localPort

    init {
        val keys = material.resolve("server.p12")
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "server", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
            "-storetype", "PKCS12", "-keystore", keys.toString(),
            "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(keys).use { load(it, PASSWORD.toCharArray()) }
        }
        trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("fixture", keyStore.getCertificate("server"))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, PASSWORD.toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = context.serverSocketFactory.createServerSocket(
            0,
            1,
            InetAddress.getLoopbackAddress(),
        ) as SSLServerSocket
        server.soTimeout = 10000
        executor.submit { serve() }
    }

    private fun serve() {
        server.accept().use { socket ->
            peer = socket
            socket.soTimeout = 10000
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            fun reply(value: String) {
                output.write("$value\r\n")
                output.flush()
            }
            fun expect(prefix: String) {
                check(input.readLine()?.startsWith(prefix) == true) { "Unexpected SMTP command" }
            }
            reply("220 controlled fixture")
            expect("EHLO")
            reply("250-controlled fixture\r\n250 AUTH PLAIN")
            expect("AUTH PLAIN")
            reply("235 Authentication successful")
            expect("MAIL FROM:")
            reply("250 OK")
            expect("RCPT TO:")
            reply("250 OK")
            expect("DATA")
            reply("354 Send message")
            while (checkNotNull(input.readLine()) != ".") { /* Consume fixture without logging content. */ }
            receivedData.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }
    }

    override fun close() {
        release.countDown()
        peer?.close()
        server.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
        check(material.toFile().deleteRecursively())
    }

    private companion object {
        const val PASSWORD = "controlled-test-password"
    }
}
