package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.util.UUID
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentCopyFilerTest {
    @get:Rule val directory = TemporaryFolder()
    private var previous: java.io.File? = null

    @Before
    fun prepare() {
        previous = BinaryTempFileBody.getTempDirectory()
        BinaryTempFileBody.setTempDirectory(directory.newFolder("mime"))
    }

    @After
    fun cleanup() = BinaryTempFileBody.setTempDirectory(previous)

    @Test
    fun `automatic copy succeeds without UIDPLUS and a repeated send or file never resubmits`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            var smtp = 0
            var copies = 0
            val filer = SentCopyFiler(SentCopyStore(database)) { _, _, claim, _ ->
                copies++
                assertThat(claim.allowAppend).isTrue()
                SentCopyResult.Saved(null)
            }
            val testSubject = OutgoingMailSender(store, { _, _ -> smtp++ }, filer)
            assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
            assertThat(filer.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.SAVED)
            assertThat(testSubject.send(account.id, draft.id)).isEqualTo(OutgoingState.SENT)
            filer.file(account.id, draft.id)
            assertThat(smtp).isEqualTo(1)
            assertThat(copies).isEqualTo(1)
        }
    }

    @Test
    fun `unknown copy is search only until explicit risk acknowledgement and history persists`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            var smtp = 0
            var appends = 0
            val testSubject = SentCopyFiler(SentCopyStore(database)) { _, _, claim, _ ->
                if (claim.allowAppend) {
                    appends++
                    if (appends == 1) error("Synthetic post-acceptance result loss")
                    SentCopyResult.Saved("42")
                } else {
                    SentCopyResult.Missing
                }
            }
            OutgoingMailSender(store, { _, _ -> smtp++ }, testSubject).send(account.id, draft.id)
            assertThat(store.get(account.id, draft.id).state).isEqualTo(OutgoingState.SENT)
            assertThat(testSubject.status(account.id, draft.id)?.state).isEqualTo(SentCopyState.UNKNOWN)
            testSubject.file(account.id, draft.id)
            assertThat(appends).isEqualTo(1)
            testSubject.file(account.id, draft.id, acknowledge = true)
            assertThat(appends).isEqualTo(2)
            assertThat(smtp).isEqualTo(1)
            assertThat(testSubject.status(account.id, draft.id)?.hasUncertainAttempt).isEqualTo(true)
        }
    }

    @Test
    fun `working copy recovers unknown across restart and acknowledgement does not reappend`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val reference = MailDatabase(path).use { database ->
            val account = account(database)
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            OutgoingMailSender(store) { _, _ -> }.send(account.id, draft.id)
            val copies = SentCopyStore(database)
            checkNotNull(copies.claim(account.id, draft.id, false))
            assertThat(copies.claim(account.id, draft.id, false)).isEqualTo(null)
            account.id to draft.id
        }
        MailDatabase(path).use { database ->
            val (account, id) = reference
            val testSubject = SentCopyFiler(SentCopyStore(database)) { _, _, claim, _ ->
                assertThat(claim.allowAppend).isFalse()
                SentCopyResult.Saved("7")
            }
            assertThat(testSubject.status(account, id)?.state).isEqualTo(SentCopyState.UNKNOWN)
            testSubject.file(account, id)
            assertThat(testSubject.status(account, id)?.state).isEqualTo(SentCopyState.SAVED)
            assertThat(testSubject.status(account, id)?.hasUncertainAttempt).isEqualTo(true)
            assertThat(OutgoingMailStore(database).get(account, id).state).isEqualTo(OutgoingState.SENT)
        }
    }

    @Test
    fun `local only preference is account scoped and unsupported delivery states cannot file`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val other = account(database)
            val copies = SentCopyStore(database)
            assertThat(copies.target(account.id)).isEqualTo("Sent")
            copies.configure(account.id, null)
            assertThat(copies.target(account.id)).isEqualTo(null)
            assertThat(copies.target(other.id)).isEqualTo("Sent")
            val store = OutgoingMailStore(database)
            val draft = queued(store, account)
            val testSubject = SentCopyFiler(copies) { _, _, _, _ -> error("No copy expected") }
            assertThat(runCatching { testSubject.file(account.id, draft.id) }.isFailure).isTrue()
            OutgoingMailSender(store, { _, _ -> }, testSubject).send(account.id, draft.id)
            assertThat(testSubject.status(account.id, draft.id)).isEqualTo(null)
            assertThat(runCatching { testSubject.file(other.id, draft.id) }.isFailure).isTrue()
            assertThat(runCatching { copies.configure(account.id, "missing") }.isFailure).isTrue()
        }
    }

    private fun queued(store: OutgoingMailStore, account: UtMailAccount): OutgoingMail {
        val draft = store.create(account.id)
        store.save(account.id, draft.id, "recipient@example.invalid", "Fixture", "Body")
        return store.queue(account, draft.id)
    }

    private fun account(database: MailDatabase): UtMailAccount {
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
            AccountIdFactory.create(),
            "Fixture",
            "fixture@example.invalid",
            settings,
            settings.copy(type = "smtp", port = 465),
            UUID.randomUUID().toString(),
        )
        AccountSettingsStorage(database).insert(account)
        PersistentBackendStorage(database, account.id.toString()).createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo("Sent", "Sent", FolderType.SENT)))
        }
        return account
    }
}
