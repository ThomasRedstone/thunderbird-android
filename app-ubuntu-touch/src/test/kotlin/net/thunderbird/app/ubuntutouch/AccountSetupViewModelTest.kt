package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountSetupViewModelTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `configure reopen edit and confirm removal of an account`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val testSubject = AccountSetupViewModel(manager, backgroundScope)
            testSubject.event(AccountSetupEvent.New)
            fill(testSubject)
            testSubject.event(AccountSetupEvent.Save)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.editing).isFalse()
            assertThat(manager.findAll().size).isEqualTo(1)
            val account = manager.findAll().single()
            testSubject.event(AccountSetupEvent.Edit(account.id))
            assertThat(testSubject.state.value.text(AccountField.IMAP_PASSWORD)).isEqualTo("")
            testSubject.event(AccountSetupEvent.Change(AccountField.NAME, TextFieldValue("Renamed")))
            testSubject.event(AccountSetupEvent.Save)
            testSubject.state.first { !it.busy }
            assertThat(manager.findById(account.id)?.displayName).isEqualTo("Renamed")
            testSubject.event(AccountSetupEvent.RequestRemoval(account.id))
            assertThat(manager.findAll().size).isEqualTo(1)
            testSubject.event(AccountSetupEvent.ConfirmRemoval)
            testSubject.state.first { !it.busy }
            assertThat(manager.findAll()).isEmpty()
        }
    }

    @Test
    fun `locked keyring and invalid settings leave form open with cleared passwords`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val secrets = FakeCredentialStore().apply { locked = true }
            val manager = UtAccountManager(AccountSettingsStorage(database), secrets)
            val testSubject = AccountSetupViewModel(manager, backgroundScope)
            testSubject.event(AccountSetupEvent.New)
            fill(testSubject)
            testSubject.event(AccountSetupEvent.Save)
            testSubject.state.first { !it.busy }
            assertThat(testSubject.state.value.editing).isTrue()
            assertThat(testSubject.state.value.message.orEmpty().contains("Unlock")).isTrue()
            assertThat(testSubject.state.value.text(AccountField.IMAP_PASSWORD)).isEqualTo("")
            assertThat(manager.findAll()).isEmpty()
            testSubject.event(AccountSetupEvent.Cancel)
            assertThat(testSubject.state.value.text(AccountField.SMTP_PASSWORD)).isEqualTo("")
        }
    }

    @Test
    fun `consecutive Maliit edits use current state without waiting for recomposition`() = runTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val testSubject = AccountSetupViewModel(manager, backgroundScope)
            testSubject.event(AccountSetupEvent.New)
            testSubject.event(
                AccountSetupEvent.Change(AccountField.IMAP_PASSWORD, TextFieldValue("abcd", TextRange(1, 3))),
            )
            testSubject.event(AccountSetupEvent.KeyboardEdit(AccountField.IMAP_PASSWORD, MaliitTextEdit.Commit("X")))
            testSubject.event(AccountSetupEvent.KeyboardEdit(AccountField.IMAP_PASSWORD, MaliitTextEdit.Commit("Y")))
            assertThat(testSubject.state.value.text(AccountField.IMAP_PASSWORD)).isEqualTo("aXYd")
            assertThat(
                testSubject.state.value.fields.getValue(AccountField.IMAP_PASSWORD).selection,
            ).isEqualTo(TextRange(3))
        }
    }

    private fun fill(testSubject: AccountSetupViewModel) {
        mapOf(
            AccountField.NAME to "Fixture",
            AccountField.ADDRESS to "fixture@example.invalid",
            AccountField.IMAP_HOST to "localhost",
            AccountField.IMAP_USER to "fixture",
            AccountField.SMTP_HOST to "localhost",
            AccountField.SMTP_USER to "fixture",
            AccountField.IMAP_PASSWORD to "fixture-incoming",
            AccountField.SMTP_PASSWORD to "fixture-outgoing",
        ).forEach { (field, value) -> testSubject.event(AccountSetupEvent.Change(field, TextFieldValue(value))) }
    }
}
