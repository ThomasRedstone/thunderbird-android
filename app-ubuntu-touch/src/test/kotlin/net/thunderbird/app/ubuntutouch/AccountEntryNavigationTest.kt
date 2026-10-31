package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountEntryNavigationTest : ComposeUiTestHarness() {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `empty mailbox opens account setup through the app UI`() = runComposeTest {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val manager = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            var accountsModel: AccountSetupViewModel? = null
            setContent {
                val scope = rememberCoroutineScope()
                val lifecycle = remember { MailWindowLifecycle(scope) }
                ThunderbirdBoltTheme {
                    MailApplicationScreen(
                        keyboard = null,
                        createAccounts = { AccountSetupViewModel(manager).also { accountsModel = it } },
                        createMailbox = { LiveMailViewModel(manager, EmptyMailRepository()) },
                        createComposer = { error("Composer should not open") },
                        lifecycle = lifecycle,
                    )
                }
            }

            var checkpoint = 0
            suspend fun awaitText(text: String) {
                checkpoint++
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (runCatching { onNodeWithText(text).fetchSemanticsNode() }.isFailure) {
                    check(System.nanoTime() < deadline) {
                        "Account setup navigation did not finish at checkpoint $checkpoint"
                    }
                    waitForIdle()
                    withContext(Dispatchers.IO) { delay(10) }
                }
            }

            awaitText("Set up account")
            onNodeWithText("Set up account").performClick()
            awaitText("Add account")
            onNodeWithText("Demo mailbox").assertDoesNotExist()
            onNodeWithText("Add account").performClick()
            awaitText("Account setup")
            onNodeWithText("Cancel").performScrollTo().performClick()
            waitForIdle()
            check(accountsModel?.state?.value?.editing == false) {
                "Cancel did not leave account editing (model=${accountsModel != null}, " +
                    "editing=${accountsModel?.state?.value?.editing}, busy=${accountsModel?.state?.value?.busy})"
            }
            awaitText("Add account")
            onNodeWithText("Mailboxes").performClick()
            awaitText("Set up account")
        }
    }
}

private class EmptyMailRepository : LiveMailRepository {
    override fun folders(account: AccountId) = emptyList<MailFolder>()
    override fun messages(account: AccountId, folder: String) = emptyList<MailSummary>()
    override fun content(account: AccountId, folder: String, uid: String): MailContent = error("No cached message")
    override fun refresh(account: AccountId, folder: String?) = Unit
    override fun download(account: AccountId, folder: String, uid: String) = Unit
}
