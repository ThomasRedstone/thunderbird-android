package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.mail.transport.smtp.NegativeSmtpReplyException
import java.util.UUID
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutgoingMailStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private var previousMimeDirectory: java.io.File? = null

    @Before
    fun prepareMimeDirectory() {
        previousMimeDirectory = BinaryTempFileBody.getTempDirectory()
        BinaryTempFileBody.setTempDirectory(directory.newFolder("mime"))
    }

    @After
    fun restoreMimeDirectory() {
        BinaryTempFileBody.setTempDirectory(previousMimeDirectory)
    }

    @Test
    fun `draft and frozen queue survive restart with stable message id and UTF8 body`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        var draftId = ""
        val accountId = MailDatabase(path).use { database ->
            val account = account(database)
            val testSubject = OutgoingMailStore(database)
            val draft = testSubject.create(account.id)
            draftId = draft.id
            testSubject.save(account.id, draft.id, "recipient@example.invalid", "Ünicode", "Hello λ\n.A dot")
            account.id
        }
        MailDatabase(path).use { database ->
            val account = UtAccountManager(
                AccountSettingsStorage(database),
                FakeCredentialStore(),
            ).findById(accountId)!!
            val testSubject = OutgoingMailStore(database)
            val restored = testSubject.get(accountId, draftId)
            assertThat(restored.state).isEqualTo(OutgoingState.DRAFT)
            testSubject.queue(account, draftId)
            assertThat(runCatching { testSubject.save(accountId, draftId, "", "Changed", "") }.isFailure).isTrue()
        }
        MailDatabase(path).use { database ->
            val testSubject = OutgoingMailStore(database)
            assertThat(testSubject.get(accountId, draftId).state).isEqualTo(OutgoingState.QUEUED)
            val raw = checkNotNull(testSubject.claim(accountId, draftId))
            val message = MimeMessage.parseMimeMessage(raw.inputStream(), true)
            assertThat(message.subject).isEqualTo("Ünicode")
            assertThat(message.messageId).isEqualTo(testSubject.get(accountId, draftId).messageId)
            val part = MimeUtility.findFirstPartByMimeType(message, "text/plain")
            assertThat(MessageExtractor.getTextFromPart(part).replace("\r\n", "\n")).isEqualTo("Hello λ\n.A dot")
            assertThat(testSubject.claim(accountId, draftId)).isEqualTo(null)
        }
    }

    @Test
    fun `interrupted sending reopens uncertain and requires explicit acknowledgement before retry`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val testSubject = OutgoingMailStore(database)
            val draft = queued(testSubject, account)
            testSubject.claim(account.id, draft.id)
            account.id to draft.id
        }
        MailDatabase(path).use { database ->
            val testSubject = OutgoingMailStore(database)
            val (account, id) = reference
            assertThat(testSubject.get(account, id).state).isEqualTo(OutgoingState.UNCERTAIN)
            assertThat(runCatching { testSubject.retry(account, id) }.isFailure).isTrue()
            assertThat(testSubject.claim(account, id)).isEqualTo(null)
            val messageId = testSubject.get(account, id).messageId
            testSubject.retry(account, id, acknowledgeDuplicateRisk = true)
            assertThat(testSubject.get(account, id).messageId).isEqualTo(messageId)
            assertThat(testSubject.get(account, id).state).isEqualTo(OutgoingState.QUEUED)
        }
    }

    @Test
    fun `successful send cannot be claimed twice and account removal cascades drafts and sent records`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            var sends = 0
            val testSubject = OutgoingMailSender(store) { _, _ ->
                sends++
                assertThat(store.claim(account.id, draft.id)).isEqualTo(null)
            }
            assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
            assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
            assertThat(sends).isEqualTo(1)
            val other = account(database)
            assertThat(store.list(other.id)).isEqualTo(emptyList())
            assertThat(runCatching { store.get(other.id, draft.id) }.isFailure).isTrue()
            database.execute("DELETE FROM accounts WHERE id=?", account.id.toString())
            assertThat(store.list(account.id)).isEqualTo(emptyList())
        }
    }

    @Test
    fun `explicit rejection is failed but possible acceptance and unknown failures are uncertain`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            listOf(
                NegativeSmtpReplyException(550, "sensitive server error") to OutgoingState.FAILED,
                MessagingException("Connection unavailable", false) to OutgoingState.FAILED,
                MessagingException("Acceptance unknown", true) to OutgoingState.UNCERTAIN,
                IllegalStateException("Unclassified") to OutgoingState.UNCERTAIN,
            ).forEach { (failure, outcome) ->
                val draft = queued(store, account)
                val testSubject = OutgoingMailSender(store) { _, _ -> throw failure }
                assertThat(testSubject.send(account.id, draft.id)).isEqualTo(outcome)
                val persisted = database.query("SELECT state FROM outgoing WHERE id=?", draft.id) { getText(0) }
                assertThat(persisted.single()).isEqualTo(outcome.name)
                assertThat(store.get(account.id, draft.id).toString().contains("sensitive")).isFalse()
            }
        }
    }

    @Test
    fun `header injection is rejected without overwriting the previous draft`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val testSubject = OutgoingMailStore(database)
            val draft = testSubject.create(account.id)
            testSubject.save(account.id, draft.id, "recipient@example.invalid", "Safe", "Body")
            assertThat(
                runCatching {
                    testSubject.save(
                        account.id,
                        draft.id,
                        "recipient@example.invalid\r\nBcc: attacker@example.invalid",
                        "",
                        "",
                    )
                }.isFailure,
            ).isTrue()
            assertThat(testSubject.get(account.id, draft.id).subject).isEqualTo("Safe")
        }
    }

    @Test
    fun `schema three upgrade preserves accounts secrets index and creates empty outbox`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val accountId = MailDatabase(path).use { database ->
            val account = account(database)
            database.execute("INSERT INTO signon_identity_index(key,identity_id) VALUES('fixture',42)")
            database.execute("DROP TABLE outgoing")
            if ("in_reply_to" in database.query("PRAGMA table_info(outgoing)") { getText(1) }) {
                database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
                database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            }
            database.execute("DROP TABLE pending_flags")
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=3")
            account.id
        }
        MailDatabase(path).use { database ->
            assertThat(OutgoingMailStore(database).list(accountId)).isEqualTo(emptyList())
            assertThat(SignonIdentityIndex(database).find("fixture")).isEqualTo(42L)
            assertThat(AccountSettingsStorage(database).all().single().id).isEqualTo(accountId)
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(12L)
        }
    }

    @Test
    fun `a rejected retry must not erase uncertainty from an earlier attempt`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            store.claim(account.id, draft.id)
            store.finish(account.id, draft.id, OutgoingState.UNCERTAIN)
            store.retry(account.id, draft.id, acknowledgeDuplicateRisk = true)
            val testSubject =
                OutgoingMailSender(store) { _, _ -> throw NegativeSmtpReplyException(550, "Rejected fixture") }
            assertThat(
                testSubject.send(account.id, draft.id, acknowledgeDuplicateRisk = true),
            ).isEqualTo(OutgoingState.UNCERTAIN)
        }
    }

    @Test
    fun `cancelling a queued retry preserves the earlier uncertain attempt`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            store.claim(account.id, draft.id)
            store.finish(account.id, draft.id, OutgoingState.UNCERTAIN)
            store.retry(account.id, draft.id, acknowledgeDuplicateRisk = true)
            val operation = MailOperation().apply { close() }
            var attempts = 0
            val testSubject = OutgoingMailSender(store) { _, _ ->
                attempts++
                error("Must not send")
            }
            assertThat(testSubject.send(account.id, draft.id, operation)).isEqualTo(OutgoingState.UNCERTAIN)
            assertThat(attempts).isEqualTo(0)
        }
    }

    @Test
    fun `schema four pending rows retain MIME and require review because old attempt history is unavailable`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            database.execute("ALTER TABLE outgoing DROP COLUMN cc")
            database.execute("ALTER TABLE outgoing DROP COLUMN bcc")
            database.execute("ALTER TABLE outgoing DROP COLUMN uncertain_attempt")
            if ("in_reply_to" in database.query("PRAGMA table_info(outgoing)") { getText(1) }) {
                database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
                database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            }
            database.execute("DROP TABLE pending_flags")
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=4")
            account.id to draft.id
        }
        MailDatabase(path).use { database ->
            val store = OutgoingMailStore(database)
            val (account, id) = reference
            val restored = store.get(account, id)
            assertThat(restored.hasUncertainAttempt).isTrue()
            assertThat(store.claim(account, id)).isEqualTo(null)
            assertThat(checkNotNull(store.claim(account, id, acknowledgeDuplicateRisk = true)).isNotEmpty()).isTrue()
        }
    }

    @Test
    fun `recipient fields survive restart and Bcc-only drafts can queue`() {
        val path = directory.root.toPath().resolve("recipients.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val testSubject = OutgoingMailStore(database)
            val draft = testSubject.create(account.id)
            testSubject.save(account.id, draft.id, "", "Blind only", "Body λ", bcc = "Blind <blind@example.invalid>")
            account.id to draft.id
        }
        MailDatabase(path).use { database ->
            val (accountId, id) = reference
            val account = UtAccountManager(
                AccountSettingsStorage(database),
                FakeCredentialStore(),
            ).findById(accountId)!!
            val testSubject = OutgoingMailStore(database)
            assertThat(testSubject.get(accountId, id).bcc).isEqualTo("Blind <blind@example.invalid>")
            testSubject.queue(account, id)
            val mime = MimeMessage.parseMimeMessage(checkNotNull(testSubject.claim(accountId, id)).inputStream(), true)
            assertThat(mime.getHeader("Bcc").single()).isEqualTo("Blind <blind@example.invalid>")
            assertThat(mime.getHeader("To").isEmpty()).isTrue()
        }
    }

    @Test
    fun `schema five upgrade preserves frozen MIME and uncertainty with empty added recipients`() {
        val path = directory.root.toPath().resolve("upgrade.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            database.execute("UPDATE outgoing SET uncertain_attempt=1 WHERE id=?", draft.id)
            val mime = database.query("SELECT mime FROM outgoing WHERE id=?", draft.id) { getBlob(0) }.single()
            database.execute("ALTER TABLE outgoing DROP COLUMN cc")
            database.execute("ALTER TABLE outgoing DROP COLUMN bcc")
            if ("in_reply_to" in database.query("PRAGMA table_info(outgoing)") { getText(1) }) {
                database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
                database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            }
            database.execute("DROP TABLE pending_flags")
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=5")
            Triple(account.id, draft.id, mime)
        }
        MailDatabase(path).use { database ->
            val testSubject = OutgoingMailStore(database)
            val (account, id, mime) = reference
            val restored = testSubject.get(account, id)
            assertThat(restored.cc).isEqualTo("")
            assertThat(restored.bcc).isEqualTo("")
            assertThat(restored.hasUncertainAttempt).isTrue()
            assertThat(restored.state).isEqualTo(OutgoingState.QUEUED)
            assertThat(testSubject.claim(account, id)).isEqualTo(null)
            assertThat(checkNotNull(testSubject.claim(account, id, true)).contentEquals(mime)).isTrue()
        }
    }

    @Test
    fun `Cc and Bcc reject injected headers and invalid recipients without queueing`() {
        MailDatabase(directory.root.toPath().resolve("invalid.sqlite")).use { database ->
            val account = account(database)
            val testSubject = OutgoingMailStore(database)
            val draft = testSubject.create(account.id)
            for (blind in listOf(false, true)) {
                val injected = "copy@example.invalid\r\nInjected: yes"
                assertThat(
                    runCatching {
                        testSubject.save(
                            account.id,
                            draft.id,
                            "",
                            "Subject",
                            "Body",
                            cc = if (blind) "" else injected,
                            bcc = if (blind) injected else "",
                        )
                    }.isFailure,
                ).isTrue()
                testSubject.save(
                    account.id,
                    draft.id,
                    "",
                    "Subject",
                    "Body",
                    cc = if (blind) "" else "invalid",
                    bcc = if (blind) "invalid" else "",
                )
                assertThat(runCatching { testSubject.queue(account, draft.id) }.isFailure).isTrue()
                assertThat(testSubject.get(account.id, draft.id).state).isEqualTo(OutgoingState.DRAFT)
            }
        }
    }

    @Test
    fun `schema six upgrade preserves recipients and queued MIME without inventing uncertain history`() {
        val path = directory.root.toPath().resolve("version-six.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = store.create(account.id)
            store.save(
                account.id,
                draft.id,
                "recipient@example.invalid",
                "Migration",
                "Body",
                cc = "copy@example.invalid",
                bcc = "blind@example.invalid",
            )
            store.queue(account, draft.id)
            if ("in_reply_to" in database.query("PRAGMA table_info(outgoing)") { getText(1) }) {
                database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
                database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            }
            database.execute("DROP TABLE pending_flags")
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=6")
            account.id to draft.id
        }
        MailDatabase(path).use { database ->
            val (account, id) = reference
            val testSubject = OutgoingMailStore(database)
            assertThat(testSubject.get(account, id).cc).isEqualTo("copy@example.invalid")
            assertThat(testSubject.get(account, id).bcc).isEqualTo("blind@example.invalid")
            assertThat(testSubject.get(account, id).hasUncertainAttempt).isFalse()
            assertThat(testSubject.claim(account, id) != null).isTrue()
            assertThat(PendingFlagStore(database).list(account).isEmpty()).isTrue()
        }
    }

    @Test
    fun `schema seven upgrade preserves pending flags and frozen outgoing data`() {
        val path = directory.root.toPath().resolve("version-seven.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            database.execute(
                "INSERT INTO folders(account,server_id,name,type) VALUES(?,'INBOX','Inbox','INBOX')",
                account.id.toString(),
            )
            val folder = database.query("SELECT id FROM folders") { getLong(0) }.single()
            database.execute(
                "INSERT INTO pending_flags(account,folder,uid,flag,enabled,uid_validity) " +
                    "VALUES(?,?,'1','SEEN',1,42)",
                account.id.toString(),
                folder,
            )
            database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
            database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=7")
            account.id to draft.id
        }
        MailDatabase(path).use { database ->
            val (account, id) = reference
            val testSubject = OutgoingMailStore(database)
            assertThat(testSubject.get(account, id).inReplyTo).isEqualTo("")
            assertThat(testSubject.get(account, id).references).isEqualTo("")
            assertThat(testSubject.claim(account, id) != null).isTrue()
            assertThat(PendingFlagStore(database).list(account).single().uidValidity).isEqualTo(42L)
        }
    }

    @Test
    fun `schema eight upgrade keeps threading frozen MIME and creates independent Sent state`() {
        val path = directory.root.toPath().resolve("version-eight.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = store.create(
                account.id,
                ResponseSeed(
                    to = "recipient@example.invalid",
                    body = "Body",
                    inReplyTo = "<parent@example.invalid>",
                    references = "<parent@example.invalid>",
                ),
            )
            store.queue(account, draft.id)
            val raw = database.query("SELECT mime FROM outgoing WHERE id=?", draft.id) { getBlob(0) }.single()
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("ALTER TABLE accounts DROP COLUMN oauth_provider")
            database.execute("PRAGMA user_version=8")
            Triple(account.id, draft.id, raw)
        }
        MailDatabase(path).use { database ->
            val (account, id, raw) = reference
            val testSubject = OutgoingMailStore(database)
            assertThat(testSubject.get(account, id).inReplyTo).isEqualTo("<parent@example.invalid>")
            assertThat(checkNotNull(testSubject.claim(account, id)).contentEquals(raw)).isTrue()
            assertThat(SentCopyStore(database).status(account, id)).isEqualTo(null)
        }
    }

    private fun queued(store: OutgoingMailStore, account: UtMailAccount): OutgoingMail {
        val draft = store.create(account.id)
        store.save(account.id, draft.id, "recipient@example.invalid", "Fixture", "Body")
        return store.queue(account, draft.id)
    }

    private fun account(database: MailDatabase): UtMailAccount {
        val server = ServerSettings(
            "imap",
            "localhost",
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        return UtMailAccount(
            AccountIdFactory.create(),
            "Fixture",
            "fixture@example.invalid",
            server,
            server.copy(type = "smtp", port = 465),
            UUID.randomUUID().toString(),
        ).also { AccountSettingsStorage(database).insert(it) }
    }
}
