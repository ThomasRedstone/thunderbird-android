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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialCleanupViewModelTest {
    @get:Rule val directory = TemporaryFolder()
    private val path get() = directory.root.toPath().resolve("mail.sqlite")
    private lateinit var database: MailDatabase
    private lateinit var manager: UtAccountManager
    private val raw = FakeCredentialStore()

    @Before
    fun open() {
        database = MailDatabase(path)
        manager = UtAccountManager(AccountSettingsStorage(database), raw)
    }

    @After
    fun close() = database.close()

    @Test
    fun `reopened setup discovers journal and removes only obsolete credential`() = runTest {
        val active = account()
        orphan()
        database.close()
        database = MailDatabase(path)
        manager = UtAccountManager(AccountSettingsStorage(database), raw)
        val testSubject = AccountSetupViewModel(manager, backgroundScope)
        assertThat(testSubject.state.value.cleanupPending).isTrue()
        testSubject.event(AccountSetupEvent.RetryCredentialCleanup)
        val state = awaitIdle(testSubject)
        assertThat(state.cleanupPending).isFalse()
        assertThat(raw.values.keys).isEqualTo(setOf(active.credentialKey))
        assertThat(manager.passwords(active.id).incoming).isEqualTo("incoming")
    }

    @Test
    fun `locked keyring leaves pending cleanup retryable after unlock`() = runTest {
        val active = account()
        orphan()
        raw.locked = true
        val testSubject = AccountSetupViewModel(manager, backgroundScope)
        testSubject.event(AccountSetupEvent.RetryCredentialCleanup)
        var state = awaitIdle(testSubject)
        assertThat(state.cleanupPending).isTrue()
        assertThat(state.message.orEmpty().contains("Unlock")).isTrue()
        assertThat(raw.values.keys).isEqualTo(setOf(active.credentialKey, "orphan"))
        raw.locked = false
        testSubject.event(AccountSetupEvent.RetryCredentialCleanup)
        state = awaitIdle(testSubject)
        assertThat(state.cleanupPending).isFalse()
        assertThat(raw.values.keys).isEqualTo(setOf(active.credentialKey))
    }

    @Test
    fun `journal entry still referenced by active account is never advertised or deleted as obsolete`() = runTest {
        val active = account()
        AccountSettingsStorage(database).scheduleCredentialCleanup(active.credentialKey)
        val testSubject = AccountSetupViewModel(manager, backgroundScope)
        assertThat(testSubject.state.value.cleanupPending).isFalse()
        testSubject.event(AccountSetupEvent.RetryCredentialCleanup)
        awaitIdle(testSubject)
        assertThat(raw.values.keys).isEqualTo(setOf(active.credentialKey))
        assertThat(manager.passwords(active.id).outgoing).isEqualTo("outgoing")
    }

    @Test
    fun `failed account insert exposes pending cleanup after leaving form without another authorization`() = runTest {
        database.execute(
            "CREATE TRIGGER fail_insert BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'fixture'); END",
        )
        val testSubject = AccountSetupViewModel(manager, backgroundScope)
        testSubject.event(AccountSetupEvent.New)
        val fields = mapOf(
            AccountField.NAME to "Controlled",
            AccountField.ADDRESS to "fixture@example.invalid",
            AccountField.IMAP_HOST to "imap.example.invalid",
            AccountField.IMAP_USER to "fixture@example.invalid",
            AccountField.SMTP_HOST to "smtp.example.invalid",
            AccountField.SMTP_USER to "fixture@example.invalid",
            AccountField.IMAP_PASSWORD to "incoming",
            AccountField.SMTP_PASSWORD to "outgoing",
        )
        fields.forEach { (field, text) -> testSubject.event(AccountSetupEvent.Change(field, TextFieldValue(text))) }
        testSubject.event(AccountSetupEvent.Save)
        var state = awaitIdle(testSubject)
        assertThat(state.editing).isTrue()
        assertThat(state.cleanupPending).isTrue()
        assertThat(raw.values.size).isEqualTo(1)
        testSubject.event(AccountSetupEvent.Cancel)
        testSubject.event(AccountSetupEvent.RetryCredentialCleanup)
        state = awaitIdle(testSubject)
        assertThat(state.cleanupPending).isFalse()
        assertThat(raw.values.isEmpty()).isTrue()
        assertThat(manager.findAll().isEmpty()).isTrue()
    }

    @Test
    fun `cleanup request cannot discard an account form`() = runTest {
        orphan()
        val testSubject = AccountSetupViewModel(manager, backgroundScope)
        testSubject.event(AccountSetupEvent.New)
        testSubject.event(AccountSetupEvent.Change(AccountField.NAME, TextFieldValue("Keep form")))
        testSubject.event(AccountSetupEvent.RetryCredentialCleanup)
        assertThat(testSubject.state.value.editing).isTrue()
        assertThat(testSubject.state.value.busy).isFalse()
        assertThat(testSubject.state.value.text(AccountField.NAME)).isEqualTo("Keep form")
        assertThat(raw.values.keys).isEqualTo(setOf("orphan"))
    }

    private fun account(): UtMailAccount {
        val account = UtMailAccount(
            AccountIdFactory.create(),
            "Controlled",
            "fixture@example.invalid",
            server("imap", 993),
            server("smtp", 465),
            UUID.randomUUID().toString(),
        )
        manager.createWithPasswords(account, AccountPasswords("incoming", "outgoing"))
        return account
    }

    private fun server(type: String, port: Int) = ServerSettings(
        type,
        "$type.example.invalid",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture@example.invalid",
        null,
        null,
    )

    private fun orphan() {
        raw.write("orphan", byteArrayOf(1, 2, 3))
        AccountSettingsStorage(database).scheduleCredentialCleanup("orphan")
    }

    private suspend fun awaitIdle(model: AccountSetupViewModel) = withContext(Dispatchers.Default) {
        withTimeout(5000) { model.state.first { !it.busy } }
    }
}
