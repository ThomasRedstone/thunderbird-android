package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailSourceScreenTest : ComposeUiTestHarness() {
    @Test
    fun `reader source action opens cached bytes without requiring a complete download`() = runComposeTest {
        val events = mutableListOf<LiveMailEvent>()
        setContent {
            ThunderbirdBoltTheme { Surface { LiveMailScreen(reader(), onEvent = events::add) } }
        }
        onNodeWithText("View source").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(listOf(LiveMailEvent.Source()))
    }

    @Test
    fun `source pager exposes range incomplete status and safe navigation instead of mail actions`() = runComposeTest {
        val events = mutableListOf<LiveMailEvent>()
        val page = MailSourcePage(0, SOURCE_PAGE_BYTES + 1L, false, ByteArray(SOURCE_PAGE_BYTES) { 65 })
        setContent {
            ThunderbirdBoltTheme { Surface { LiveMailScreen(reader().copy(source = page), onEvent = events::add) } }
        }
        onNodeWithText("Cached message source · Incomplete message").assertIsDisplayed()
        onNodeWithText("Bytes 1–32768 of 32769").assertIsDisplayed()
        onNodeWithText("Previous page").assertIsNotEnabled()
        onNodeWithText("Next page").assertIsEnabled().performClick()
        onNodeWithText("Back to message").performClick()
        onNodeWithText("Sync folder").assertDoesNotExist()
        assertThat(events).isEqualTo(listOf(LiveMailEvent.Source(SOURCE_PAGE_BYTES.toLong()), LiveMailEvent.Back))
    }

    private fun reader() = LiveMailState(
        emptyList(),
        account = AccountIdFactory.create(),
        folder = MailFolder("INBOX", "Inbox"),
        content = MailContent(MailSummary("1", "Fixture", "Sender", false), "Cached", false),
    )
}
