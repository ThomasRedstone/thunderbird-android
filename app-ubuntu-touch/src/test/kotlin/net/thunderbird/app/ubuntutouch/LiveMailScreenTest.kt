package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class LiveMailScreenTest : ComposeUiTestHarness() {
    @Test
    fun `cached search input submits query and clear restores the folder`() = runComposeTest {
        var event: LiveMailEvent? = null
        val state = LiveMailState(
            emptyList(),
            account = AccountIdFactory.create(),
            folder = MailFolder("INBOX", "Inbox"),
        )
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state) { event = it } } }
        onNodeWithText("Search cached sender, subject and text").performTextInput("Budget λ")
        onNodeWithText("Search").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Search("Budget λ"))
        onNodeWithText("Clear search").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Search(""))
        onNodeWithText("Offline search · Uncached text, HTML and attachments are not searched").assertIsDisplayed()
    }

    @Test
    fun `cancel requests teardown but keeps navigation disabled until the worker settles`() = runComposeTest {
        val state = mutableStateOf(
            LiveMailState(
                emptyList(),
                account = AccountIdFactory.create(),
                busy = true,
                canCancel = true,
            ),
        )
        var event: LiveMailEvent? = null
        setContent {
            ThunderbirdBoltTheme {
                LiveMailScreen(state.value) {
                    event = it
                    state.value = state.value.copy(cancelling = true)
                }
            }
        }
        onNodeWithText("Cancel operation").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Cancel)
        onNodeWithText("Cancel operation").assertIsNotEnabled()
        onNodeWithText("Back").assertIsNotEnabled()
        onNodeWithText("Cancelling…").assertIsDisplayed()
    }

    @Test
    fun `partial reader offers explicit full download and displays cached plain text`() = runComposeTest {
        val summary = MailSummary("1", "Fixture subject", "Fixture sender", true)
        var event: LiveMailEvent? = null
        val state = LiveMailState(
            emptyList(),
            account = AccountIdFactory.create(),
            folder = MailFolder("INBOX", "Inbox"),
            content = MailContent(summary, "Cached plain text", false),
        )
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state) { event = it } } }
        onNodeWithText("Cached plain text").assertIsDisplayed()
        onNodeWithText("Only a preview is cached.").assertIsDisplayed()
        onNodeWithText("Download full message").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Download)
    }

    @Test
    fun `busy mailbox disables sync and navigation`() = runComposeTest {
        val state = LiveMailState(emptyList(), account = AccountIdFactory.create(), busy = true)
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state) {} } }
        onNodeWithText("Working…").assertIsDisplayed()
        onNodeWithText("Refresh folders").assertIsNotEnabled()
        onNodeWithText("Back").assertIsNotEnabled()
    }

    @Test
    fun `reader offers read and star toggles and disables them while working`() = runComposeTest {
        val state = mutableStateOf(
            LiveMailState(
                emptyList(),
                account = AccountIdFactory.create(),
                folder = MailFolder("INBOX", "Inbox"),
                content = MailContent(MailSummary("1", "Fixture", "Sender", true), "Body", true),
            ),
        )
        var event: LiveMailEvent? = null
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state.value) { event = it } } }
        onNodeWithText("Mark read").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.ChangeFlag(Flag.SEEN, true))
        onNodeWithText("Star message").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.ChangeFlag(Flag.FLAGGED, true))
        state.value = state.value.copy(busy = true)
        onNodeWithText("Mark read").assertIsNotEnabled()
        onNodeWithText("Star message").assertIsNotEnabled()
    }

    @Test
    fun `reply actions dispatch distinct modes and require complete plain text`() = runComposeTest {
        val state = mutableStateOf(
            LiveMailState(
                emptyList(),
                account = AccountIdFactory.create(),
                folder = MailFolder("INBOX", "Inbox"),
                content = MailContent(MailSummary("1", "Fixture", "Sender", false), "Body", true),
            ),
        )
        var event: LiveMailEvent? = null
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state.value) { event = it } } }
        onNodeWithText("Reply").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Respond(ResponseMode.REPLY))
        onNodeWithText("Reply all").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Respond(ResponseMode.REPLY_ALL))
        onNodeWithText("Forward text").performClick()
        assertThat(event).isEqualTo(LiveMailEvent.Respond(ResponseMode.FORWARD))
        state.value = state.value.copy(content = checkNotNull(state.value.content).copy(complete = false))
        onNodeWithText("Reply").assertIsNotEnabled()
        state.value =
            state.value.copy(content = checkNotNull(state.value.content).copy(complete = true, plainText = false))
        onNodeWithText("Forward text").assertIsNotEnabled()
    }
}
