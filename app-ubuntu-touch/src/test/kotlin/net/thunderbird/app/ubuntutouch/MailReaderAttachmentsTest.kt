package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.assertIsDisplayed
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailReaderAttachmentsTest : ComposeUiTestHarness() {
    @Test
    fun `reader shows attachment names and MIME types alongside message body`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state(complete = true), onEvent = {}) }
            }
        }
        onNodeWithText("Cached body").assertIsDisplayed()
        onNodeWithText("Attachments (1)").assertIsDisplayed()
        onNodeWithText("café.bin").assertIsDisplayed()
        onNodeWithText("application/octet-stream").assertIsDisplayed()
        onNodeWithText("Download the complete message to list attachments.").assertDoesNotExist()
    }

    @Test
    fun `incomplete reader requests complete download and hides provisional metadata`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state(complete = false), onEvent = {}) }
            }
        }
        onNodeWithText("Download the complete message to list attachments.").assertIsDisplayed()
        onNodeWithText("café.bin").assertDoesNotExist()
        onNodeWithText("Attachments (1)").assertDoesNotExist()
    }

    private fun state(complete: Boolean) = LiveMailState(
        emptyList(),
        account = AccountIdFactory.create(),
        folder = MailFolder("INBOX", "Inbox"),
        content = MailContent(
            MailSummary("1", "Subject", "Sender", false),
            "Cached body",
            complete,
            attachments = listOf(MailAttachmentInfo("café.bin", "application/octet-stream")),
        ),
    )
}
