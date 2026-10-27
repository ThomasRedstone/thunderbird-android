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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignatureSettingsViewModelTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `keyboard multiline preferences save on close and remain account scoped`() = runTest {
        fixture { _, accounts, store ->
            val account = accounts.findAll().first().id
            val testSubject = SignatureSettingsViewModel(accounts, store, backgroundScope)
            testSubject.event(SignatureSettingsEvent.Account(account))
            testSubject.state.first { !it.busy }
            testSubject.event(SignatureSettingsEvent.Keyboard(MaliitTextEdit.Commit("Fixture λ")))
            testSubject.event(SignatureSettingsEvent.Keyboard(MaliitTextEdit.Enter))
            testSubject.event(SignatureSettingsEvent.Keyboard(MaliitTextEdit.Commit("Team")))
            testSubject.event(SignatureSettingsEvent.Enabled)
            testSubject.event(SignatureSettingsEvent.Forwards)
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(store.read(account)).isEqualTo(MailSignature("Fixture λ\nTeam", true, true, false))
            assertThat(store.read(accounts.findAll().last().id)).isEqualTo(MailSignature())
            assertThat(testSubject.state.value.dirty).isFalse()
            assertThat(testSubject.state.value.toString()).isEqualTo("SignatureSettingsState(redacted)")
        }
    }

    @Test
    fun `failed save retains edits blocks close and supports retry without raw error details`() = runTest {
        fixture { database, accounts, store ->
            val account = accounts.findAll().first().id
            val testSubject = SignatureSettingsViewModel(accounts, store, backgroundScope)
            testSubject.event(SignatureSettingsEvent.Account(account))
            testSubject.state.first { !it.busy }
            testSubject.event(SignatureSettingsEvent.Text(TextFieldValue("Fixture")))
            database.execute(
                "CREATE TRIGGER fail_signature BEFORE INSERT ON signatures BEGIN SELECT RAISE(ABORT,'sensitive'); END",
            )
            assertThat(testSubject.flushForClose()).isFalse()
            assertThat(testSubject.state.value.input.text).isEqualTo("Fixture")
            assertThat(testSubject.state.value.dirty).isTrue()
            assertThat(testSubject.state.value.notice.orEmpty().contains("sensitive")).isFalse()
            testSubject.event(SignatureSettingsEvent.Back)
            assertThat(testSubject.state.value.account).isEqualTo(account)
            testSubject.event(SignatureSettingsEvent.Account(accounts.findAll().last().id))
            assertThat(testSubject.state.value.account).isEqualTo(account)
            database.execute("DROP TRIGGER fail_signature")
            assertThat(testSubject.flushForClose()).isTrue()
            assertThat(store.read(account).text).isEqualTo("Fixture")
        }
    }

    @Test
    fun `discard needs review and account replacement prevents stale save`() = runTest {
        fixture { _, accounts, store ->
            val account = accounts.findAll().first().id
            val testSubject = SignatureSettingsViewModel(accounts, store, backgroundScope)
            testSubject.event(SignatureSettingsEvent.Account(account))
            testSubject.state.first { !it.busy }
            testSubject.event(SignatureSettingsEvent.Text(TextFieldValue("Unsaved")))
            accounts.updateSync(account) { it.copy(displayName = "Changed") }
            assertThat(testSubject.flushForClose()).isFalse()
            assertThat(store.read(account)).isEqualTo(MailSignature())
            testSubject.event(SignatureSettingsEvent.ConfirmDiscard)
            assertThat(testSubject.state.value.dirty).isTrue()
            testSubject.event(SignatureSettingsEvent.ReviewDiscard)
            testSubject.event(SignatureSettingsEvent.Keep)
            testSubject.event(SignatureSettingsEvent.ConfirmDiscard)
            assertThat(testSubject.state.value.dirty).isTrue()
            testSubject.event(SignatureSettingsEvent.ReviewDiscard)
            testSubject.event(SignatureSettingsEvent.ConfirmDiscard)
            assertThat(testSubject.state.value.account).isEqualTo(null)
            assertThat(testSubject.state.value.dirty).isFalse()
        }
    }

    private suspend fun fixture(work: suspend (MailDatabase, UtAccountManager, MailSignatureStore) -> Unit) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val accounts = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            repeat(2) {
                val server =
                    ServerSettings(
                        "imap",
                        "localhost",
                        993,
                        ConnectionSecurity.SSL_TLS_REQUIRED,
                        AuthType.PLAIN,
                        "fixture",
                        null,
                        null,
                    )
                accounts.createWithPasswords(
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
            work(database, accounts, MailSignatureStore(database))
        }
    }
}
