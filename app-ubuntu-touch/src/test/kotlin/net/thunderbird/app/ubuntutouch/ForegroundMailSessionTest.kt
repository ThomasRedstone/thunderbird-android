package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.MissingCapabilityException
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeUtility
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import jakarta.mail.Session as JakartaSession
import jakarta.mail.internet.MimeMessage as JakartaMessage

class ForegroundMailSessionTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var database: MailDatabase
    private lateinit var accounts: UtAccountManager
    private lateinit var account: UtMailAccount
    private var previousMimeDirectory: java.io.File? = null
    private var logEvents = 0
    private val logger = DefaultLogger(object : LogSink {
        override val level: LogLevel = LogLevel.VERBOSE
        override fun log(event: LogEvent) {
            logEvents++
        }
    })

    @Before
    fun setupAccount() {
        server.purgeEmailFromAllMailboxes()
        previousMimeDirectory = BinaryTempFileBody.getTempDirectory()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        accounts = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
        account = UtMailAccount(
            AccountIdFactory.create(),
            "Controlled mailbox",
            EMAIL,
            settings("imap", server.imaps.port),
            settings("smtp", server.smtps.port),
            UUID.randomUUID().toString(),
        )
        accounts.createWithPasswords(account, AccountPasswords(PASSWORD, PASSWORD))
    }

    @After
    fun closeDatabase() {
        database.close()
        BinaryTempFileBody.setTempDirectory(previousMimeDirectory)
    }

    @Test
    fun `real IMAP discovers folders and persists initial incremental flags and deletion state`() {
        deliver("First", "First controlled body")
        val testSubject = factory()
        testSubject.open(account.id).use { session ->
            session.checkSettings()
            session.refreshFolders()
            session.sync("INBOX")
        }
        val storage = PersistentBackendStorage(database, account.id.toString())
        val inbox = storage.getFolder("INBOX")
        val firstUid = inbox.getMessageServerIds().single()
        assertThat(inbox.readMessage(firstUid)?.subject).isEqualTo("First")
        val firstBody = MimeUtility.findFirstPartByMimeType(inbox.readMessage(firstUid)!!, "text/plain")
        assertThat(MessageExtractor.getTextFromPart(firstBody).trim()).isEqualTo("First controlled body")
        assertThat(Flag.X_DOWNLOADED_FULL in inbox.getMessageFlags(firstUid)).isTrue()
        deliver("Second", "Second controlled body")
        testSubject.open(account.id).use { it.sync("INBOX") }
        assertThat(inbox.getMessageServerIds().size).isEqualTo(2)
        assertThat(inbox.getMessageServerIds().contains(firstUid)).isTrue()
        withOracleInbox { folder ->
            folder.messages.first { it.subject == "First" }.setFlag(jakarta.mail.Flags.Flag.SEEN, true)
            folder.messages.first { it.subject == "Second" }.setFlag(jakarta.mail.Flags.Flag.DELETED, true)
            folder.expunge()
        }
        testSubject.open(account.id).use { it.sync("INBOX") }
        assertThat(inbox.getMessageServerIds()).isEqualTo(setOf(firstUid))
        assertThat(Flag.SEEN in inbox.getMessageFlags(firstUid)).isTrue()
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        val reopened = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        assertThat(reopened.readMessage(firstUid)?.subject).isEqualTo("First")
        val repository = PersistentLiveMailRepository(database, factory())
        assertThat(repository.folders(account.id).any { it.id == "INBOX" }).isTrue()
        assertThat(repository.messages(account.id, "INBOX").single().unread).isFalse()
        val content = repository.content(account.id, "INBOX", firstUid)
        assertThat(content.body.trim()).isEqualTo("First controlled body")
        assertThat(content.complete).isTrue()
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `real SMTP delivers expected message and dot-stuffed body without logging content`() {
        val testSubject = factory()
        val message = MimeMessage.parseMimeMessage(raw("Outgoing", "Hello\r\n.A leading dot").byteInputStream(), true)
        testSubject.open(account.id).use { it.send(message) }
        assertThat(server.waitForIncomingEmail(5000, 1)).isTrue()
        val received = server.receivedMessages.single()
        assertThat(received.subject).isEqualTo("Outgoing")
        assertThat(received.content.toString().trim()).isEqualTo("Hello\r\n.A leading dot")
        assertThat(received.allRecipients.single().toString()).isEqualTo(RECIPIENT)
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `persistent outbox delivers frozen MIME once through the real SMTP transport`() {
        val sessions = factory()
        val store = OutgoingMailStore(database)
        val draft = store.create(account.id)
        store.save(account.id, draft.id, RECIPIENT, "Outbox fixture", "Hello λ\r\n.A leading dot")
        val queued = store.queue(account, draft.id)
        val testSubject = OutgoingMailSender(store, SessionOutgoingTransport(sessions))
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
        assertThat(server.waitForIncomingEmail(5000, 1)).isTrue()
        val received = server.receivedMessages.single()
        assertThat(received.messageID).isEqualTo(queued.messageId)
        assertThat(received.subject).isEqualTo("Outbox fixture")
        assertThat(received.content.toString().trim()).isEqualTo("Hello λ\r\n.A leading dot")
        assertThat(received.allRecipients.single().toString()).isEqualTo(RECIPIENT)
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `acceptance followed by lost local outcome does not blindly redeliver`() {
        val sessions = factory()
        val store = OutgoingMailStore(database)
        val draft = store.create(account.id)
        store.save(account.id, draft.id, RECIPIENT, "Uncertain fixture", "Controlled body")
        store.queue(account, draft.id)
        val transport = SessionOutgoingTransport(sessions)
        var attempts = 0
        val testSubject = OutgoingMailSender(store) { id, mime ->
            attempts++
            transport.send(id, mime)
            // Fault injection after the real SMTP server accepts; no reliable outcome reaches the sender.
            throw net.thunderbird.core.common.exception.MessagingException("Lost outcome fixture", true)
        }
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.UNCERTAIN)
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.UNCERTAIN)
        assertThat(attempts).isEqualTo(1)
        assertThat(server.receivedMessages.size).isEqualTo(1)
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `unknown certificate and rejected credentials fail instead of creating successful mailbox state`() {
        val testSubject = factory(trusted = false)
        val certificateFailure = runCatching {
            testSubject.open(account.id).use { it.checkSettings() }
        }.exceptionOrNull()
        assertThat(certificateFailure).isNotNull().isInstanceOf(CertificateValidationException::class.java)
        accounts.replacePasswords(account.id, AccountPasswords("rejected-fixture", "rejected-fixture"))
        val authenticated = factory()
        val authenticationFailure = runCatching {
            authenticated.open(account.id).use { it.checkSettings() }
        }.exceptionOrNull()
        assertThat(authenticationFailure).isNotNull().isInstanceOf(AuthenticationFailedException::class.java)
        assertThat(PersistentBackendStorage(database, account.id.toString()).getFolderServerIds().isEmpty()).isTrue()
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `interrupted connection fails synchronization and retains the previous cache`() {
        deliver("Cached", "Available offline")
        val testSubject = factory()
        testSubject.open(account.id).use {
            it.refreshFolders()
            it.sync("INBOX")
        }
        val folder = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        val uid = folder.getMessageServerIds().single()
        val unavailablePort = java.net.ServerSocket(0).use { it.localPort }
        accounts.updateSync(account.id) { it.copy(incoming = it.incoming.copy(port = unavailablePort)) }
        assertThat(runCatching { testSubject.open(account.id).use { it.sync("INBOX") } }.isFailure).isTrue()
        assertThat(folder.getMessageServerIds()).isEqualTo(setOf(uid))
        assertThat(folder.readMessage(uid)?.subject).isEqualTo("Cached")
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `explicit body fetch completes a message beyond the automatic download limit`() {
        val body = "Controlled large body\r\n".repeat(60000)
        deliver("Large fixture", body)
        val testSubject = factory()
        testSubject.open(account.id).use {
            it.refreshFolders()
            it.sync("INBOX")
        }
        val folder = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        val uid = folder.getMessageServerIds().single()
        assertThat(Flag.X_DOWNLOADED_PARTIAL in folder.getMessageFlags(uid)).isTrue()
        testSubject.open(account.id).use { it.downloadMessage("INBOX", uid) }
        assertThat(Flag.X_DOWNLOADED_FULL in folder.getMessageFlags(uid)).isTrue()
        val part = MimeUtility.findFirstPartByMimeType(folder.readMessage(uid)!!, "text/plain")
        assertThat(MessageExtractor.getTextFromPart(part).replace("\r\n", "\n").trim())
            .isEqualTo(body.replace("\r\n", "\n").trim())
    }

    @Test
    fun `required STARTTLS refuses servers without the capability for receiving and sending`() {
        accounts.updateSync(account.id) {
            it.copy(
                incoming = it.incoming.copy(
                    port = server.imap.port,
                    connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED,
                ),
                outgoing = it.outgoing.copy(
                    port = server.smtp.port,
                    connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED,
                ),
            )
        }
        val testSubject = factory()
        val receivingFailure = runCatching { testSubject.open(account.id).use { it.checkSettings() } }.exceptionOrNull()
        assertThat(receivingFailure).isNotNull().isInstanceOf(MissingCapabilityException::class.java)
        val message = MimeMessage.parseMimeMessage(raw("Must not send", "Requires encryption").byteInputStream(), true)
        val sendingFailure = runCatching { testSubject.open(account.id).use { it.send(message) } }.exceptionOrNull()
        assertThat(sendingFailure).isNotNull().isInstanceOf(MissingCapabilityException::class.java)
        assertThat(server.receivedMessages.size).isEqualTo(0)
        assertThat(logEvents).isEqualTo(0)
    }

    private fun factory(trusted: Boolean = true) = ForegroundMailSessionFactory(
        accounts,
        database,
        if (trusted) JvmTrustedSocketFactory(systemTrustManager(trustedCertificates)) else JvmTrustedSocketFactory(),
        logger,
        directory.root.toPath().resolve("mime"),
    )

    @Test
    fun `queued To Cc Bcc deliver to each mailbox without exposing blind recipients`() {
        server.setUser("copy@example.invalid", "copy", PASSWORD)
        server.setUser("blind@example.invalid", "blind", PASSWORD)
        val store = OutgoingMailStore(database)
        val draft = store.create(account.id)
        store.save(
            account.id,
            draft.id,
            RECIPIENT,
            "Recipient privacy",
            "Private fixture body",
            cc = "Copy <copy@example.invalid>",
            bcc = "Blind <blind@example.invalid>",
        )
        store.queue(account, draft.id)
        val testSubject = OutgoingMailSender(store, SessionOutgoingTransport(factory()))
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
        assertThat(server.waitForIncomingEmail(5000, 3)).isTrue()
        for (user in listOf("recipient", "copy", "blind")) {
            withOracleInbox(user) { folder ->
                val message = folder.messages.single()
                assertThat(message.subject).isEqualTo("Recipient privacy")
                assertThat(message.getHeader("Bcc")).isEqualTo(null)
                assertThat(message.getHeader("To").single()).isEqualTo(RECIPIENT)
                assertThat(message.getHeader("Cc").single()).isEqualTo("Copy <copy@example.invalid>")
                assertThat(message.allHeaders.toList().any { "blind@example.invalid" in it.value }).isFalse()
            }
        }
    }

    @Test
    fun `offline read and star changes survive restart and reconcile with actual IMAP flags`() {
        deliver("Actions", "Controlled action body")
        var repository = PersistentLiveMailRepository(database, factory())
        repository.refresh(account.id, "INBOX")
        val uid = repository.messages(account.id, "INBOX").single().id
        repository.changeFlag(account.id, "INBOX", uid, Flag.SEEN, true)
        repository.changeFlag(account.id, "INBOX", uid, Flag.FLAGGED, true)
        assertThat(repository.content(account.id, "INBOX", uid).summary.unread).isFalse()
        assertThat(repository.content(account.id, "INBOX", uid).summary.starred).isTrue()
        withOracleInbox { folder ->
            assertThat(folder.messages.single().isSet(jakarta.mail.Flags.Flag.SEEN)).isFalse()
            assertThat(folder.messages.single().isSet(jakarta.mail.Flags.Flag.FLAGGED)).isFalse()
        }
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        repository = PersistentLiveMailRepository(database, factory())
        assertThat(repository.pendingFlags(account.id)).isEqualTo(2)
        assertThat(repository.content(account.id, "INBOX", uid).summary.starred).isTrue()
        repository.refresh(account.id, "INBOX")
        assertThat(repository.pendingFlags(account.id)).isEqualTo(0)
        withOracleInbox { folder ->
            assertThat(folder.messages.single().isSet(jakarta.mail.Flags.Flag.SEEN)).isTrue()
            assertThat(folder.messages.single().isSet(jakarta.mail.Flags.Flag.FLAGGED)).isTrue()
        }
        repository.changeFlag(account.id, "INBOX", uid, Flag.SEEN, false)
        repository.changeFlag(account.id, "INBOX", uid, Flag.FLAGGED, false)
        repository.refresh(account.id, "INBOX")
        withOracleInbox { folder ->
            assertThat(folder.messages.single().isSet(jakarta.mail.Flags.Flag.SEEN)).isFalse()
            assertThat(folder.messages.single().isSet(jakarta.mail.Flags.Flag.FLAGGED)).isFalse()
        }
        assertThat(repository.content(account.id, "INBOX", uid).summary.unread).isTrue()
        assertThat(repository.content(account.id, "INBOX", uid).summary.starred).isFalse()
    }

    @Test
    fun `mailbox identity mismatch never applies queued flags to a reused UID and allows local discard`() {
        deliver("UID safety", "Controlled body")
        val testSubject = PersistentLiveMailRepository(database, factory())
        testSubject.refresh(account.id, "INBOX")
        val uid = testSubject.messages(account.id, "INBOX").single().id
        val folder = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        val validity = checkNotNull(folder.getFolderExtraNumber("imapUidValidity"))
        folder.setFolderExtraNumber("imapUidValidity", validity + 1)
        testSubject.changeFlag(account.id, "INBOX", uid, Flag.FLAGGED, true)
        assertThat(runCatching { testSubject.refresh(account.id, "INBOX") }.isFailure).isTrue()
        assertThat(testSubject.pendingFlags(account.id)).isEqualTo(1)
        withOracleInbox { remote ->
            assertThat(remote.messages.single().isSet(jakarta.mail.Flags.Flag.FLAGGED)).isFalse()
        }
        assertThat(testSubject.content(account.id, "INBOX", uid).summary.starred).isFalse()
        testSubject.discardFlags(account.id)
        assertThat(testSubject.pendingFlags(account.id)).isEqualTo(0)
    }

    @Test
    fun `acknowledging an older flag assignment cannot remove a newer local decision`() {
        deliver("Coalescing", "Controlled body")
        val repository = PersistentLiveMailRepository(database, factory())
        repository.refresh(account.id, "INBOX")
        val uid = repository.messages(account.id, "INBOX").single().id
        val testSubject = PendingFlagStore(database)
        testSubject.enqueue(account.id, "INBOX", uid, Flag.FLAGGED, true)
        val old = testSubject.list(account.id).single()
        testSubject.enqueue(account.id, "INBOX", uid, Flag.FLAGGED, false)
        testSubject.acknowledge(account.id, old)
        assertThat(testSubject.list(account.id).single().enabled).isFalse()
        assertThat(repository.content(account.id, "INBOX", uid).summary.starred).isFalse()
        val latest = testSubject.list(account.id).single()
        testSubject.acknowledge(account.id, latest)
        assertThat(testSubject.list(account.id).isEmpty()).isTrue()
        assertThat(repository.content(account.id, "INBOX", uid).summary.starred).isFalse()
    }

    @Test
    fun `acknowledged flag is durable locally even before another network sync`() {
        deliver("Ack durability", "Controlled body")
        var repository = PersistentLiveMailRepository(database, factory())
        repository.refresh(account.id, "INBOX")
        val uid = repository.messages(account.id, "INBOX").single().id
        val actions = PendingFlagStore(database)
        actions.enqueue(account.id, "INBOX", uid, Flag.FLAGGED, true)
        actions.acknowledge(account.id, actions.list(account.id).single())
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        repository = PersistentLiveMailRepository(database, factory())
        assertThat(repository.pendingFlags(account.id)).isEqualTo(0)
        assertThat(repository.content(account.id, "INBOX", uid).summary.starred).isTrue()
    }

    @Test
    fun `pending flags are account isolated and account removal cascades them`() {
        deliver("Isolation", "Controlled body")
        val repository = PersistentLiveMailRepository(database, factory())
        repository.refresh(account.id, "INBOX")
        val uid = repository.messages(account.id, "INBOX").single().id
        val testSubject = PendingFlagStore(database)
        testSubject.enqueue(account.id, "INBOX", uid, Flag.FLAGGED, true)
        val other = account.copy(id = AccountIdFactory.create(), credentialKey = UUID.randomUUID().toString())
        AccountSettingsStorage(database).insert(other)
        val action = testSubject.list(account.id).single()
        assertThat(testSubject.list(other.id).isEmpty()).isTrue()
        testSubject.acknowledge(other.id, action)
        testSubject.discard(other.id)
        assertThat(testSubject.list(account.id).single()).isEqualTo(action)
        AccountSettingsStorage(database).delete(account.id)
        assertThat(testSubject.list(account.id).isEmpty()).isTrue()
    }

    private fun deliver(subject: String, body: String) {
        val message =
            JakartaMessage(JakartaSession.getInstance(Properties()), raw(subject, body, EMAIL).byteInputStream())
        server.userManager.getUser("fixture").deliver(message)
    }

    private fun withOracleInbox(user: String = "fixture", block: (jakarta.mail.Folder) -> Unit) {
        val context = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(systemTrustManager(trustedCertificates)), null)
        }
        val properties = Properties().apply {
            put("mail.imaps.ssl.socketFactory", context.socketFactory)
            put("mail.imaps.ssl.checkserveridentity", "true")
        }
        JakartaSession.getInstance(properties).getStore("imaps").use { store ->
            store.connect("localhost", server.imaps.port, user, PASSWORD)
            store.getFolder("INBOX").use { folder ->
                folder.open(jakarta.mail.Folder.READ_WRITE)
                block(folder)
            }
        }
    }

    private fun settings(type: String, port: Int) = ServerSettings(
        type,
        "localhost",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture",
        null,
        null,
    )

    companion object {
        private const val EMAIL = "fixture@example.invalid"
        private const val RECIPIENT = "recipient@example.invalid"
        private const val PASSWORD = "fixture-password"
        private lateinit var material: Path
        private lateinit var server: GreenMail
        private lateinit var trustedCertificates: KeyStore
        private val previousProperties = mutableMapOf<String, String?>()

        @BeforeClass
        @JvmStatic
        fun startControlledServer() {
            material = Files.createTempDirectory("mail-controlled-tls-")
            val path = material.resolve("server.p12")
            val process = ProcessBuilder(
                "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", path.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
            val keys = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(path).use { load(it, PASSWORD.toCharArray()) }
            }
            trustedCertificates = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("fixture", keys.getCertificate("server"))
            }
            mapOf(
                "greenmail.tls.keystore.file" to path.toString(),
                "greenmail.tls.keystore.password" to PASSWORD,
            ).forEach { (key, value) ->
                previousProperties[key] = System.getProperty(key)
                System.setProperty(key, value)
            }
            server = GreenMail(
                arrayOf(
                    ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS),
                    ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS),
                    ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
                    ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP),
                ),
            )
            server.start()
            server.setUser(EMAIL, "fixture", PASSWORD)
            server.setUser(RECIPIENT, "recipient", PASSWORD)
        }

        @AfterClass
        @JvmStatic
        fun stopControlledServer() {
            if (::server.isInitialized) server.stop()
            previousProperties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            if (::material.isInitialized) material.toFile().deleteRecursively()
        }

        private fun raw(subject: String, body: String, recipient: String = RECIPIENT): String =
            "From: $EMAIL\r\nTo: $recipient\r\nSubject: $subject\r\n" +
                "Message-ID: <${UUID.randomUUID()}@example.invalid>\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n$body\r\n"
    }
}
