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
import java.util.concurrent.Executors
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

@Suppress("LargeClass") // Real-engine protocol cases share one owned TLS/server/database lifecycle fixture.
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
    fun `confirmed move preserves unrelated deleted mail and resyncs both cached folders`() {
        deliver("Unrelated", "Keep this deleted message")
        val key = prepareMoveSource()
        withOracleInbox { marker ->
            marker.messages.single { it.subject == "Unrelated" }.setFlag(jakarta.mail.Flags.Flag.DELETED, true)
            val store = MailMoveStore(database)
            val queued = store.enqueue(key, "Move fixture target")
            val testSubject = MailMoveRunner(store, SessionMailMoveTransport(factory()))
            assertThat(testSubject.run(queued, MailOperation()).state).isEqualTo(MailMoveState.COMPLETE)
            withOracleInbox { inbox ->
                assertThat(inbox.messageCount).isEqualTo(1)
                assertThat(inbox.messages.single().subject).isEqualTo("Unrelated")
            }
            withOracleMoveTarget { target ->
                assertThat(target.messageCount).isEqualTo(1)
                assertThat(target.messages.single().subject).isEqualTo("Move fixture")
            }
        }
        factory().open(account.id).use {
            it.sync("INBOX")
            it.sync("Move fixture target")
        }
        val storage = PersistentBackendStorage(database, account.id.toString())
        assertThat(storage.getFolder("INBOX").isMessagePresent(key.uid)).isFalse()
        assertThat(storage.getFolder("Move fixture target").getMessageServerIds().size).isEqualTo(1)
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `lost move copy acknowledgement retains unknown outcome without a second server copy`() {
        val key = prepareMoveSource()
        val material = StartTlsMaterial(directory.root.toPath())
        StartTlsGateway(true, server.imap.port, material.context, dropMoveAcknowledgement = true).use { gateway ->
            accounts.updateSync(account.id) {
                it.copy(
                    incoming = settings(
                        "imap",
                        gateway.port,
                    ).copy(connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED),
                )
            }
            val sessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(material.trust)),
                logger,
                directory.root.toPath().resolve("move-mime"),
            )
            val store = MailMoveStore(database)
            val queued = store.enqueue(key, "Move fixture target")
            val testSubject = MailMoveRunner(store, SessionMailMoveTransport(sessions))
            val outcome = testSubject.run(queued, MailOperation())
            assertThat(outcome.state).isEqualTo(MailMoveState.UNKNOWN)
            assertThat(gateway.droppedMoveAcknowledgements.get()).isEqualTo(1)
            assertThat(runCatching { testSubject.run(outcome, MailOperation()) }.isFailure).isTrue()
            withOracleMoveTarget { assertThat(it.messageCount).isEqualTo(1) }
            assertThat(
                PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX").isMessagePresent(key.uid),
            ).isTrue()
        }
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `cancellation releases stalled move handoff without waiting for account deregistration`() {
        val key = prepareMoveSource()
        val material = StartTlsMaterial(directory.root.toPath())
        StartTlsGateway(true, server.imap.port, material.context, stallMoveAcknowledgement = true).use { gateway ->
            accounts.updateSync(account.id) {
                it.copy(
                    incoming = settings(
                        "imap",
                        gateway.port,
                    ).copy(connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED),
                )
            }
            val sessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(material.trust)),
                logger,
                directory.root.toPath().resolve("move-cancel-mime"),
            )
            val store = MailMoveStore(database)
            val queued = store.enqueue(key, "Move fixture target")
            val operation = MailOperation()
            val workers = Executors.newFixedThreadPool(2)
            try {
                val testSubject = MailMoveRunner(store, SessionMailMoveTransport(sessions))
                val pending = workers.submit<MailMove> { testSubject.run(queued, operation) }
                check(gateway.moveTransferStarted.await(5, TimeUnit.SECONDS))
                val closing = workers.submit { operation.close() }
                closing.get(2, TimeUnit.SECONDS)
                assertThat(pending.get(2, TimeUnit.SECONDS).state).isEqualTo(MailMoveState.UNKNOWN)
                withOracleMoveTarget { assertThat(it.messageCount).isEqualTo(1) }
            } finally {
                gateway.close()
                workers.shutdownNow()
                check(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
        assertThat(logEvents).isEqualTo(0)
    }

    private fun prepareMoveSource(): MailMessageKey {
        deliver("Move fixture", "Move controlled body λ")
        withOracleInbox { inbox ->
            val target = inbox.store.getFolder("Move fixture target")
            if (!target.exists()) check(target.create(jakarta.mail.Folder.HOLDS_MESSAGES))
        }
        factory().open(account.id).use {
            it.refreshFolders()
            it.sync("INBOX")
            it.sync("Move fixture target")
        }
        val messages = PersistentLiveMailRepository(database, factory()).messages(account.id, "INBOX")
        return MailMessageKey(account.id, "INBOX", messages.single { it.subject == "Move fixture" }.id)
    }

    private fun withOracleMoveTarget(block: (jakarta.mail.Folder) -> Unit) = withOracleInbox { inbox ->
        inbox.store.getFolder("Move fixture target").use { target ->
            target.open(jakarta.mail.Folder.READ_WRITE)
            block(target)
        }
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
    fun `bulk assignments persist offline and reconcile each selected message after restart`() {
        deliver("Bulk one", "Controlled body")
        deliver("Bulk two", "Controlled body")
        var testSubject = PersistentLiveMailRepository(database, factory())
        testSubject.refresh(account.id, "INBOX")
        val keys = testSubject.messages(account.id, "INBOX").map { MailMessageKey(account.id, "INBOX", it.id) }
        testSubject.changeFlags(keys, Flag.SEEN, true)
        testSubject.changeFlags(keys, Flag.FLAGGED, true)
        assertThat(testSubject.pendingFlags(account.id)).isEqualTo(4)
        assertThat(testSubject.messages(account.id, "INBOX").all { !it.unread && it.starred }).isTrue()
        withOracleInbox { remote ->
            assertThat(remote.messages.none { it.isSet(jakarta.mail.Flags.Flag.FLAGGED) }).isTrue()
        }
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        testSubject = PersistentLiveMailRepository(database, factory())
        assertThat(testSubject.pendingFlags(account.id)).isEqualTo(4)
        val result = testSubject.syncQueuedFlags(listOf(account.id), MailOperation())
        assertThat(result).isEqualTo(FlagSyncResult(1, 0))
        assertThat(testSubject.pendingFlags(account.id)).isEqualTo(0)
        withOracleInbox { remote ->
            assertThat(remote.messages.size).isEqualTo(2)
            assertThat(
                remote.messages.all {
                    it.isSet(jakarta.mail.Flags.Flag.SEEN) && it.isSet(jakarta.mail.Flags.Flag.FLAGGED)
                },
            ).isTrue()
        }
    }

    @Test
    fun `invalid final bulk target rolls back all replacements and retains earlier queued decisions`() {
        deliver("Atomic batch", "Controlled body")
        val testSubject = PersistentLiveMailRepository(database, factory())
        testSubject.refresh(account.id, "INBOX")
        val uid = testSubject.messages(account.id, "INBOX").single().id
        val key = MailMessageKey(account.id, "INBOX", uid)
        testSubject.changeFlag(account.id, "INBOX", uid, Flag.FLAGGED, false)
        val before = PendingFlagStore(database).list(account.id)
        assertThat(
            runCatching {
                testSubject.changeFlags(listOf(key, key.copy(uid = "absent")), Flag.FLAGGED, true)
            }.isFailure,
        ).isTrue()
        assertThat(PendingFlagStore(database).list(account.id)).isEqualTo(before)
        assertThat(testSubject.messages(account.id, "INBOX").single().starred).isFalse()
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

    @Test
    fun `reply draft survives reopen and SMTP retains threading and quoted body`() {
        val raw = "From: sender@example.invalid\r\nReply-To: recipient@example.invalid\r\nTo: $EMAIL\r\n" +
            "Subject: Response fixture\r\nMessage-ID: <response-parent@example.invalid>\r\n" +
            "References: <response-root@example.invalid>\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nBody λ\r\n"
        server.userManager.getUser("fixture").deliver(
            JakartaMessage(JakartaSession.getInstance(Properties()), raw.byteInputStream()),
        )
        var repository = PersistentLiveMailRepository(database, factory())
        repository.refresh(account.id, "INBOX")
        val uid = repository.messages(account.id, "INBOX").single().id
        val draft = repository.respond(account.id, "INBOX", uid, ResponseMode.REPLY)
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        val store = OutgoingMailStore(database)
        assertThat(store.get(account.id, draft).inReplyTo).isEqualTo("<response-parent@example.invalid>")
        store.queue(account, draft)
        val testSubject = OutgoingMailSender(store, SessionOutgoingTransport(factory()))
        assertThat(testSubject.send(account.id, draft)).isEqualTo(OutgoingState.SENT)
        withOracleInbox("recipient") { folder ->
            val received = folder.messages.single()
            assertThat(received.subject).isEqualTo("Re: Response fixture")
            assertThat(received.getHeader("In-Reply-To").single()).isEqualTo("<response-parent@example.invalid>")
            assertThat(received.getHeader("References").single())
                .isEqualTo("<response-root@example.invalid> <response-parent@example.invalid>")
            assertThat(received.content.toString().contains("> Body λ")).isTrue()
        }
        repository = PersistentLiveMailRepository(database, factory())
        val cached = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        cached.setMessageFlag(uid, Flag.X_DOWNLOADED_FULL, false)
        val before = store.list(account.id).size
        assertThat(
            runCatching {
                repository.respond(account.id, "INBOX", uid, ResponseMode.FORWARD)
            }.isFailure,
        ).isTrue()
        assertThat(store.list(account.id).size).isEqualTo(before)
    }

    @Test
    fun `acknowledged SMTP automatically files one private Sent copy retaining Bcc and threading`() {
        prepareSentFolder()
        server.setUser("blind@example.invalid", "blind", PASSWORD)
        val store = OutgoingMailStore(database)
        val draft = store.create(
            account.id,
            ResponseSeed(
                to = RECIPIENT,
                subject = "Sent privacy",
                body = "Body λ",
                inReplyTo = "<parent@example.invalid>",
                references = "<parent@example.invalid>",
            ),
        )
        store.save(account.id, draft.id, RECIPIENT, draft.subject, draft.body, bcc = "blind@example.invalid")
        store.queue(account, draft.id)
        val filer = SentCopyFiler(SentCopyStore(database), assertedCopyTransport())
        val testSubject = OutgoingMailSender(store, SessionOutgoingTransport(factory()), filer)
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
        assertThat(filer.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.SAVED)
        testSubject.send(account.id, draft.id)
        filer.file(account.id, draft.id)
        withOracleSent { folder ->
            val saved = folder.messages.single()
            assertThat(saved.isSet(jakarta.mail.Flags.Flag.SEEN)).isTrue()
            assertThat(saved.getHeader("Message-ID").single()).isEqualTo(draft.messageId)
            assertThat(saved.getHeader("Bcc").single()).isEqualTo("blind@example.invalid")
            assertThat(saved.getHeader("In-Reply-To").single()).isEqualTo("<parent@example.invalid>")
            assertThat(saved.content.toString().trim()).isEqualTo("Body λ")
        }
        withOracleInbox("recipient") { folder ->
            assertThat(folder.messages.single().getHeader("Bcc")).isEqualTo(null)
        }
    }

    @Test
    fun `lost copy result reconciles actual server copy without another APPEND or SMTP delivery`() {
        prepareSentFolder()
        val store = OutgoingMailStore(database)
        val draft = store.create(account.id)
        store.save(account.id, draft.id, RECIPIENT, "Lost copy result", "Body")
        store.queue(account, draft.id)
        val real = SessionSentCopyTransport(factory())
        var loseResult = true
        val filer = SentCopyFiler(SentCopyStore(database)) { id, outgoing, claim, operation ->
            val result = real.save(id, outgoing, claim, operation)
            if (loseResult) {
                loseResult = false
                error("Synthetic result loss after actual APPEND")
            }
            result
        }
        val testSubject = OutgoingMailSender(store, SessionOutgoingTransport(factory()), filer)
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
        assertThat(filer.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.UNKNOWN)
        withOracleSent { assertThat(it.messageCount).isEqualTo(1) }
        filer.file(account.id, draft.id)
        assertThat(filer.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.SAVED)
        assertThat(filer.status(account.id, draft.id)?.hasUncertainAttempt).isEqualTo(true)
        testSubject.send(account.id, draft.id)
        withOracleSent { assertThat(it.messageCount).isEqualTo(1) }
        withOracleInbox("recipient") { assertThat(it.messageCount).isEqualTo(1) }
    }

    @Test
    fun `dropped wire APPEND acknowledgement recovers by search after database reopen`() {
        prepareSentFolder()
        val material = StartTlsMaterial(directory.root.toPath())
        StartTlsGateway(true, server.imap.port, material.context, dropAppendAcknowledgement = true).use { gateway ->
            accounts.updateSync(account.id) {
                it.copy(
                    incoming = settings(
                        "imap",
                        gateway.port,
                    ).copy(connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED),
                )
            }
            val sessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(material.trust)),
                logger,
                directory.root.toPath().resolve("wire-mime"),
            )
            val store = OutgoingMailStore(database)
            val draft = store.create(account.id)
            store.save(account.id, draft.id, RECIPIENT, "Wire acknowledgement loss", "Retained body λ")
            store.queue(account, draft.id)
            // SMTP retains its independently trusted TLS connection; only IMAP passes through the fault gateway.
            val filer = SentCopyFiler(SentCopyStore(database), SessionSentCopyTransport(sessions))
            val testSubject = OutgoingMailSender(store, SessionOutgoingTransport(factory()), filer)
            assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
            assertThat(gateway.droppedAppendAcknowledgements.get()).isEqualTo(1)
            assertThat(filer.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.UNKNOWN)
            withOracleSent { assertThat(it.messageCount).isEqualTo(1) }
            database.close()
            database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
            val reopenedSessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(material.trust)),
                logger,
                directory.root.toPath().resolve("wire-mime"),
            )
            val recovered = SentCopyFiler(SentCopyStore(database), SessionSentCopyTransport(reopenedSessions))
            assertThat(recovered.file(account.id, draft.id)?.state).isEqualTo(SentCopyState.SAVED)
            assertThat(recovered.status(account.id, draft.id)?.hasUncertainAttempt).isEqualTo(true)
            withOracleSent { folder ->
                assertThat(folder.messageCount).isEqualTo(1)
                assertThat(folder.messages.single().content.toString().trim()).isEqualTo("Retained body λ")
            }
            withOracleInbox("recipient") { assertThat(it.messageCount).isEqualTo(1) }
        }
    }

    @Test
    fun `authenticated truncated body sync preserves cached mail and recovers on reconnect`() {
        deliver("Existing cache", "Previously complete body")
        factory().open(account.id).use {
            it.refreshFolders()
            it.sync("INBOX")
        }
        val original = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        val originalUid = original.getMessageServerIds().single()
        deliver("Interrupted new mail", "New body λ ".repeat(100))
        val material = StartTlsMaterial(directory.root.toPath())
        StartTlsGateway(true, server.imap.port, material.context, dropFetchBody = true).use { gateway ->
            accounts.updateSync(account.id) {
                it.copy(
                    incoming = settings(
                        "imap",
                        gateway.port,
                    ).copy(connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED),
                )
            }
            val sessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(material.trust)),
                logger,
                directory.root.toPath().resolve("interrupted-mime"),
            )
            val failure = runCatching { sessions.open(account.id).use { it.sync("INBOX") } }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(gateway.truncatedFetchBodies.get()).isEqualTo(1)
            assertThat(original.readMessage(originalUid)?.subject).isEqualTo("Existing cache")
            assertThat(Flag.X_DOWNLOADED_FULL in original.getMessageFlags(originalUid)).isTrue()
            val oldBody = MimeUtility.findFirstPartByMimeType(original.readMessage(originalUid)!!, "text/plain")
            assertThat(MessageExtractor.getTextFromPart(oldBody).trim()).isEqualTo("Previously complete body")
            original.getMessageServerIds().filter { it != originalUid }.forEach {
                assertThat(Flag.X_DOWNLOADED_FULL in original.getMessageFlags(it)).isFalse()
            }
        }
        accounts.updateSync(account.id) { it.copy(incoming = settings("imap", server.imaps.port)) }
        database.close()
        database = MailDatabase(directory.root.toPath().resolve("mail.sqlite"))
        factory().open(account.id).use { it.sync("INBOX") }
        val recovered = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        assertThat(recovered.getMessageServerIds().size).isEqualTo(2)
        val newUid = recovered.getMessageServerIds().single { it != originalUid }
        assertThat(Flag.X_DOWNLOADED_FULL in recovered.getMessageFlags(newUid)).isTrue()
        val body = MimeUtility.findFirstPartByMimeType(recovered.readMessage(newUid)!!, "text/plain")
        assertThat(MessageExtractor.getTextFromPart(body).trim()).isEqualTo("New body λ ".repeat(100).trim())
        assertThat(logEvents).isEqualTo(0)
    }

    @Test
    fun `cancellation closes authenticated stalled body transfer and retains complete cached mail`() =
        stalledBodyTransfer { it.close() }

    @Test
    fun `credential replacement closes authenticated stalled transfer and preserves cached mail`() =
        stalledBodyTransfer { accounts.replacePasswords(account.id, AccountPasswords(PASSWORD, PASSWORD)) }

    private fun stalledBodyTransfer(interrupt: (MailOperation) -> Unit) {
        deliver("Cached before cancel", "Retained complete body")
        factory().open(account.id).use {
            it.refreshFolders()
            it.sync("INBOX")
        }
        val cached = PersistentBackendStorage(database, account.id.toString()).getFolder("INBOX")
        val uid = cached.getMessageServerIds().single()
        deliver("Stalled new mail", "Body λ ".repeat(100))
        val material = StartTlsMaterial(directory.root.toPath())
        StartTlsGateway(true, server.imap.port, material.context, stallFetchBody = true).use { gateway ->
            accounts.updateSync(account.id) {
                it.copy(
                    incoming = settings(
                        "imap",
                        gateway.port,
                    ).copy(connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED),
                )
            }
            val sessions = ForegroundMailSessionFactory(
                accounts,
                database,
                JvmTrustedSocketFactory(systemTrustManager(material.trust)),
                logger,
                directory.root.toPath().resolve("cancel-body-mime"),
            )
            val operation = MailOperation()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Throwable?> {
                    runCatching { sessions.open(account.id, operation).use { it.sync("INBOX") } }.exceptionOrNull()
                }
                check(gateway.bodyTransferStarted.await(3, TimeUnit.SECONDS))
                assertThat(result.isDone).isFalse()
                interrupt(operation)
                assertThat(result.get(3, TimeUnit.SECONDS)).isNotNull()
                check(gateway.clientConnectionClosed.await(3, TimeUnit.SECONDS))
                assertThat(cached.readMessage(uid)?.subject).isEqualTo("Cached before cancel")
                assertThat(Flag.X_DOWNLOADED_FULL in cached.getMessageFlags(uid)).isTrue()
                val body = MimeUtility.findFirstPartByMimeType(cached.readMessage(uid)!!, "text/plain")
                assertThat(MessageExtractor.getTextFromPart(body).trim()).isEqualTo("Retained complete body")
                assertThat(logEvents).isEqualTo(0)
            } finally {
                operation.close()
                worker.shutdownNow()
                check(worker.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `existing provider Sent copy is found instead of appending another copy`() {
        prepareSentFolder()
        val store = OutgoingMailStore(database)
        val draft = store.create(account.id)
        store.save(account.id, draft.id, RECIPIENT, "Provider auto filing", "Body")
        store.queue(account, draft.id)
        val realSmtp = SessionOutgoingTransport(factory())
        val realCopy = SessionSentCopyTransport(factory())
        val filer = SentCopyFiler(SentCopyStore(database), realCopy)
        val testSubject = OutgoingMailSender(store, { id, mime ->
            realSmtp.send(id, mime)
            realCopy.save(id, draft.id, SentCopyClaim("Sent", mime, true, draft.messageId), null)
        }, filer)
        assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
        withOracleSent { assertThat(it.messageCount).isEqualTo(1) }
        assertThat(filer.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.SAVED)
    }

    private fun assertedCopyTransport(): SentCopyTransport {
        val real = SessionSentCopyTransport(factory())
        return SentCopyTransport { account, id, claim, operation ->
            try {
                real.save(account, id, claim, operation)
            } catch (failure: Exception) {
                throw AssertionError("Controlled Sent copy failed", failure)
            }
        }
    }

    private fun prepareSentFolder() {
        withOracleInbox { inbox ->
            val sent = inbox.store.getFolder("Sent")
            if (!sent.exists()) check(sent.create(jakarta.mail.Folder.HOLDS_MESSAGES))
        }
        PersistentLiveMailRepository(database, factory()).refresh(account.id, null)
        SentCopyStore(database).configure(account.id, "Sent")
    }

    private fun withOracleSent(block: (jakarta.mail.Folder) -> Unit) = withOracleInbox { inbox ->
        inbox.store.getFolder("Sent").use { sent ->
            sent.open(jakarta.mail.Folder.READ_WRITE)
            block(sent)
        }
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
            val tls = StartTlsMaterial(material)
            trustedCertificates = tls.trust
            mapOf(
                "greenmail.tls.keystore.file" to tls.path.toString(),
                "greenmail.tls.keystore.password" to tls.password,
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
