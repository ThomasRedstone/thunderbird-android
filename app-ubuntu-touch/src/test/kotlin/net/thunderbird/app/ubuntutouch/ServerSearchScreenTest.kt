package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class ServerSearchScreenTest : ComposeUiTestHarness() {
    @Test
    fun `search entry is restricted to an idle selected folder`() {
        val account = AccountIdFactory.create()
        val folder = MailFolder("Archive", "Archive")
        val testSubject = LiveMailState(emptyList(), account = account, folder = folder)
        assertThat(testSubject.serverSearchRequest()).isEqualTo(ServerSearchEvent.Open(account, folder))
        assertThat(testSubject.copy(busy = true).serverSearchRequest()).isNull()
        assertThat(testSubject.copy(searchScope = CachedSearchScope.ALL_ACCOUNTS).serverSearchRequest()).isNull()
        assertThat(testSubject.copy(folder = null).serverSearchRequest()).isNull()
    }

    @Test
    fun `screen exposes explicit online action limits result opening and reader back`() = runComposeTest {
        val account = AccountIdFactory.create()
        val message = MailSummary("42", "Fixture", "Sender", true)
        val results = ServerMailResults(account, "INBOX", 1, "binding", listOf(message), 2)
        val current = mutableStateOf(
            ServerSearchState(
                account,
                "Fixture account",
                MailFolder("INBOX", "Inbox"),
                TextFieldValue("Needle"),
                results = results,
            ),
        )
        val events = mutableListOf<ServerSearchEvent>()
        setContent { ThunderbirdBoltTheme { ServerSearchScreen(current.value, events::add) } }
        onNodeWithText("Search online").performScrollTo().assertIsEnabled().performClick()
        assertThat(events.last()).isEqualTo(ServerSearchEvent.Search)
        onNodeWithText("Results are limited. Refine your search to find other messages.")
            .performScrollTo().assertExists()
        onNodeWithText("Fixture · Sender").performScrollTo().performClick()
        assertThat(events.last()).isEqualTo(ServerSearchEvent.Read("42"))
        current.value = current.value.copy(content = MailContent(message, "Controlled body", true))
        onNodeWithText("Back to results").performClick()
        assertThat(events.last()).isEqualTo(ServerSearchEvent.Back)
        onNodeWithText("Search online").assertDoesNotExist()
    }

    @Test
    fun `busy screen disables query submission and permits only one cancellation`() = runComposeTest {
        val account = AccountIdFactory.create()
        val current = mutableStateOf(
            ServerSearchState(
                account,
                "Fixture",
                MailFolder("INBOX", "Inbox"),
                TextFieldValue("Needle"),
                busy = true,
            ),
        )
        val events = mutableListOf<ServerSearchEvent>()
        setContent { ThunderbirdBoltTheme { ServerSearchScreen(current.value, events::add) } }
        onNodeWithText("Search online").performScrollTo().assertIsNotEnabled()
        onNodeWithText("Cancel").performClick()
        assertThat(events).isEqualTo(listOf(ServerSearchEvent.Cancel))
        current.value = current.value.copy(cancelling = true)
        onNodeWithText("Cancel").assertIsNotEnabled()
    }
}
