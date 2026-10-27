package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResponseNavigationTest : ComposeUiTestHarness() {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `reader reply opens the saved draft in the correct composer without sending`() = runComposeTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val account = account(database)
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            val store = OutgoingMailStore(database)
            val repository = ResponseNavigationRepository(store)
            setContent {
                val scope = rememberCoroutineScope()
                val lifecycle = remember { MailWindowLifecycle(scope) }
                ThunderbirdBoltTheme {
                    MailApplicationScreen(
                        DemoMailViewModel(emptyList()),
                        null,
                        createAccounts = { error("Unused account setup") },
                        createMailbox = { LiveMailViewModel(manager, repository) },
                        createComposer = {
                            ComposerViewModel(
                                manager,
                                store,
                                OutgoingMailSender(store) { _, _ ->
                                    error("Opening a response must not send")
                                },
                            )
                        },
                        lifecycle = lifecycle,
                    )
                }
            }
            suspend fun awaitText(text: String) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (runCatching { onNodeWithText(text).fetchSemanticsNode() }.isFailure) {
                    check(System.nanoTime() < deadline) { "Response navigation did not finish" }
                    waitForIdle()
                    withContext(Dispatchers.IO) { delay(10) }
                }
            }
            onNodeWithText("Mailboxes").performClick()
            awaitText("Fixture account")
            onNodeWithText("Fixture account").performClick()
            awaitText("Inbox")
            onNodeWithText("Inbox").performClick()
            awaitText("Unread · Original\nSender")
            onNodeWithText("Unread · Original\nSender").performClick()
            awaitText("Cached source body")
            onNodeWithText("Reply").performClick()
            awaitText("Re: Original")
            onNodeWithText("Re: Original").assertIsDisplayed()
            onNodeWithText("recipient@example.invalid").assertIsDisplayed()
            onNodeWithText("Send now").assertDoesNotExist()
            assertThat(store.list(account.id).single().state).isEqualTo(OutgoingState.DRAFT)
            assertThat(store.list(account.id).single().inReplyTo).isEqualTo("<parent@example.invalid>")
        }
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
        return UtMailAccount(
            AccountIdFactory.create(),
            "Fixture account",
            "fixture@example.invalid",
            settings,
            settings.copy(type = "smtp", port = 465),
            UUID.randomUUID().toString(),
        ).also {
            AccountSettingsStorage(database).insert(it)
        }
    }
}

private class ResponseNavigationRepository(private val store: OutgoingMailStore) : LiveMailRepository {
    private val folder = MailFolder("INBOX", "Inbox")
    private val summary = MailSummary("1", "Original", "Sender", true)
    override fun folders(account: AccountId) = listOf(folder)
    override fun messages(account: AccountId, folder: String) = listOf(summary)
    override fun content(
        account: AccountId,
        folder: String,
        uid: String,
    ) = MailContent(summary, "Cached source body", true)
    override fun refresh(account: AccountId, folder: String?) = error("Navigation must be offline")
    override fun download(account: AccountId, folder: String, uid: String) = error("Navigation must be offline")
    override fun respond(account: AccountId, folder: String, uid: String, mode: ResponseMode): String =
        store.create(
            account,
            ResponseSeed(
                to = "recipient@example.invalid",
                subject = "Re: Original",
                body = "\n\n> Cached source body",
                inReplyTo = "<parent@example.invalid>",
            ),
        ).id
}
