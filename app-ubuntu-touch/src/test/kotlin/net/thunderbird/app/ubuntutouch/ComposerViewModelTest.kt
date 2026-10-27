package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `new draft snapshots enabled account signature and reopen never adds another copy`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val account = manager.findAll().single().id
            val store = OutgoingMailStore(database)
            val signatures = MailSignatureStore(database)
            signatures.save(account, MailSignature("Fixture signature", enabled = true))
            val testSubject = ComposerViewModel(
                manager,
                store,
                OutgoingMailSender(store) { _, _ -> },
                backgroundScope,
                signatures = signatures,
            )
            testSubject.event(ComposerEvent.Account(account))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.New)
            val created = testSubject.state.first { !it.busy }
            val id = checkNotNull(created.selected).id
            assertThat(created.fields.getValue(ComposeField.BODY).text).isEqualTo("\n\n-- \nFixture signature")
            signatures.save(account, MailSignature("Replacement", enabled = true))
            testSubject.event(ComposerEvent.Back)
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.Open(id))
            val reopened = testSubject.state.first { !it.busy }
            assertThat(reopened.fields.getValue(ComposeField.BODY).text).isEqualTo("\n\n-- \nFixture signature")
        }
    }

    @Test
    fun `autosave and back preserve latest edits and queue does not send`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val store = OutgoingMailStore(database)
            var attempts = 0
            val sender = OutgoingMailSender(store) { _, _ -> attempts++ }
            val testSubject = ComposerViewModel(manager, store, sender, backgroundScope)
            testSubject.event(ComposerEvent.Account(manager.findAll().single().id))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.New)
            testSubject.state.first { !it.busy }
            val id = checkNotNull(testSubject.state.value.selected).id
            testSubject.event(ComposerEvent.Change(ComposeField.TO, TextFieldValue("recipient@example.invalid")))
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Old")))
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Latest")))
            testSubject.event(ComposerEvent.Keyboard(ComposeField.BODY, MaliitTextEdit.Commit("Hello λ\nBody")))
            advanceTimeBy(401)
            testSubject.state.first { !it.dirty }
            assertThat(store.get(manager.findAll().single().id, id).subject).isEqualTo("Latest")
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Before back")))
            testSubject.event(ComposerEvent.Back)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected).isEqualTo(null)
            testSubject.event(ComposerEvent.Open(id))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.fields.getValue(ComposeField.SUBJECT).text).isEqualTo("Before back")
            testSubject.event(ComposerEvent.Queue)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.QUEUED)
            assertThat(attempts).isEqualTo(0)
            testSubject.event(ComposerEvent.Send)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.SENT)
            assertThat(attempts).isEqualTo(1)
        }
    }

    @Test
    fun `uncertain delivery needs explicit confirmation and cancel performs no attempt`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val store = OutgoingMailStore(database)
            val account = manager.findAll().single()
            val draft = store.create(account.id)
            store.save(account.id, draft.id, "recipient@example.invalid", "Fixture", "Body")
            store.queue(account, draft.id)
            var attempts = 0
            val sender = OutgoingMailSender(store) { _, _ ->
                attempts++
                if (attempts == 1) error("Synthetic sensitive server failure")
            }
            val testSubject = ComposerViewModel(manager, store, sender, backgroundScope)
            testSubject.event(ComposerEvent.Account(account.id))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.Open(draft.id))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.Send)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.UNCERTAIN)
            testSubject.event(ComposerEvent.Retry)
            assertThat(testSubject.state.value.confirmRetry).isTrue()
            assertThat(attempts).isEqualTo(1)
            testSubject.event(ComposerEvent.CancelRetry)
            testSubject.event(ComposerEvent.ConfirmRetry)
            assertThat(attempts).isEqualTo(1)
            testSubject.event(ComposerEvent.Retry)
            testSubject.event(ComposerEvent.ConfirmRetry)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.SENT)
            assertThat(attempts).isEqualTo(2)
        }
    }

    @Test
    fun `failed draft save keeps input and does not leak raw errors`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val store = OutgoingMailStore(database)
            val testSubject = ComposerViewModel(manager, store, OutgoingMailSender(store) { _, _ -> }, backgroundScope)
            testSubject.event(ComposerEvent.Account(manager.findAll().single().id))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.New)
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Keep\r\nInjected")))
            testSubject.event(ComposerEvent.Save)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.dirty).isTrue()
            assertThat(testSubject.state.value.fields.getValue(ComposeField.SUBJECT).text).isEqualTo("Keep\r\nInjected")
            assertThat(testSubject.state.value.notice.orEmpty().contains("Keep")).isFalse()
            testSubject.event(ComposerEvent.Back)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected != null).isTrue()
        }
    }

    @Test
    fun `close flush persists edits before debounce and failed flush preserves editor`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val store = OutgoingMailStore(database)
            val testSubject = ComposerViewModel(manager, store, OutgoingMailSender(store) { _, _ -> }, backgroundScope)
            val account = manager.findAll().single().id
            testSubject.event(ComposerEvent.Account(account))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.New)
            testSubject.state.first { !it.busy }
            val id = checkNotNull(testSubject.state.value.selected).id
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Immediate close")))
            testSubject.event(ComposerEvent.Change(ComposeField.CC, TextFieldValue("copy@example.invalid")))
            testSubject.event(ComposerEvent.Keyboard(ComposeField.BCC, MaliitTextEdit.Commit("blind@example.invalid")))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(store.get(account, id).subject).isEqualTo("Immediate close")
            assertThat(store.get(account, id).cc).isEqualTo("copy@example.invalid")
            assertThat(store.get(account, id).bcc).isEqualTo("blind@example.invalid")
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Invalid\r\nheader")))
            assertThat(testSubject.flushForClose()).isFalse()
            assertThat(testSubject.state.value.dirty).isTrue()
            assertThat(
                testSubject.state.value.fields.getValue(ComposeField.SUBJECT).text,
            ).isEqualTo("Invalid\r\nheader")
            assertThat(store.get(account, id).subject).isEqualTo("Immediate close")
            testSubject.event(ComposerEvent.Change(ComposeField.SUBJECT, TextFieldValue("Recovered")))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(store.get(account, id).subject).isEqualTo("Recovered")
        }
    }

    @Test
    fun `background flush saves immediately without waiting for debounce`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = manager(database)
            val store = OutgoingMailStore(database)
            val testSubject = ComposerViewModel(manager, store, OutgoingMailSender(store) { _, _ -> }, backgroundScope)
            val account = manager.findAll().single().id
            testSubject.event(ComposerEvent.Account(account))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.New)
            testSubject.state.first { !it.busy }
            val id = checkNotNull(testSubject.state.value.selected).id
            testSubject.event(ComposerEvent.Change(ComposeField.BODY, TextFieldValue("Background edit")))
            testSubject.flushOnBackground()
            testSubject.state.first { !it.busy }
            assertThat(store.get(account, id).body).isEqualTo("Background edit")
            assertThat(testSubject.state.value.dirty).isFalse()
        }
    }

    @Test
    fun `resumed response keeps threading during edits and cannot replace dirty input`() = runTest {
        MailDatabase(directory.root.toPath().resolve("response.sqlite")).use { database ->
            val manager = manager(database)
            val account = manager.findAll().single().id
            val store = OutgoingMailStore(database)
            val draft = store.create(
                account,
                ResponseSeed(
                    to = "recipient@example.invalid",
                    subject = "Re: Fixture",
                    body = "> Original",
                    inReplyTo = "<parent@example.invalid>",
                    references = "<parent@example.invalid>",
                ),
            )
            val testSubject = ComposerViewModel(manager, store, OutgoingMailSender(store) { _, _ -> }, backgroundScope)
            testSubject.event(ComposerEvent.Resume(ComposeRequest(account, draft.id, "Saved response")))
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.selected?.id).isEqualTo(draft.id)
            assertThat(testSubject.state.value.notice).isEqualTo("Saved response")
            testSubject.event(ComposerEvent.Change(ComposeField.BODY, TextFieldValue("Reply\n> Original")))
            testSubject.event(ComposerEvent.Resume(ComposeRequest(account, "must-not-open")))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(store.get(account, draft.id).body).isEqualTo("Reply\n> Original")
            assertThat(store.get(account, draft.id).inReplyTo).isEqualTo("<parent@example.invalid>")
        }
    }

    @Test
    fun `filing retry is independent from SMTP and close waits for local Sent settings`() = runTest {
        MailDatabase(directory.root.toPath().resolve("sent.sqlite")).use { database ->
            val manager = manager(database)
            val account = manager.findAll().single().id
            database.execute(
                "INSERT INTO folders(account,server_id,name,type) VALUES(?,'Sent','Sent','SENT')",
                account.toString(),
            )
            val store = OutgoingMailStore(database)
            val draft = store.create(account)
            store.save(account, draft.id, "recipient@example.invalid", "Fixture", "Body")
            store.queue(manager.findAll().single(), draft.id)
            var smtp = 0
            var appends = 0
            lateinit var testSubject: ComposerViewModel
            val filer = SentCopyFiler(SentCopyStore(database)) { _, _, claim, _ ->
                assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.SENT)
                if (claim.allowAppend) {
                    appends++
                    if (appends == 1) error("Synthetic result loss")
                    SentCopyResult.Saved("2")
                } else {
                    SentCopyResult.Missing
                }
            }
            val sender = OutgoingMailSender(store, { _, _ -> smtp++ }, filer)
            testSubject = ComposerViewModel(manager, store, sender, backgroundScope, filer)
            testSubject.event(ComposerEvent.Resume(ComposeRequest(account, draft.id)))
            testSubject.state.first { !it.busy }
            testSubject.event(ComposerEvent.Send)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.sentCopy?.state).isEqualTo(SentCopyState.UNKNOWN)
            testSubject.event(ComposerEvent.FileSent)
            testSubject.state.first { !it.busy }
            assertThat(appends).isEqualTo(1)
            testSubject.event(ComposerEvent.ConfirmCopy)
            assertThat(appends).isEqualTo(1)
            testSubject.event(ComposerEvent.ReviewCopy)
            testSubject.event(ComposerEvent.CancelCopy)
            testSubject.event(ComposerEvent.ConfirmCopy)
            assertThat(appends).isEqualTo(1)
            testSubject.event(ComposerEvent.ReviewCopy)
            testSubject.event(ComposerEvent.ConfirmCopy)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.sentCopy?.state).isEqualTo(SentCopyState.SAVED)
            assertThat(smtp).isEqualTo(1)
            assertThat(appends).isEqualTo(2)
            testSubject.event(ComposerEvent.SentFolder(null))
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(testSubject.state.value.sentTarget).isEqualTo(null)
            assertThat(testSubject.state.value.sentCopy?.folder).isEqualTo("Sent")
        }
    }

    private fun manager(database: MailDatabase): UtAccountManager {
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
        return UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore()).apply {
            createWithPasswords(
                UtMailAccount(
                    AccountIdFactory.create(),
                    "Fixture",
                    "fixture@example.invalid",
                    server,
                    server.copy(type = "smtp", port = 465),
                    UUID.randomUUID().toString(),
                ),
                AccountPasswords("fixture", "fixture"),
            )
        }
    }
}
