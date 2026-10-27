package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class ComposerScreenTest : ComposeUiTestHarness() {
    @Test
    fun `composer edits recipients and offers offline queue without a send action`() = runComposeTest {
        var event: ComposerEvent? = null
        val state = ComposerState(
            emptyList(),
            account = AccountIdFactory.create(),
            selected = draft(OutgoingState.DRAFT),
            fields = ComposeField.entries.associateWith { TextFieldValue("") },
        )
        setContent { ThunderbirdBoltTheme { ComposerScreen(state, { event = it }, null) } }
        onNodeWithText("To").performTextInput("recipient@example.invalid")
        assertThat((event as ComposerEvent.Change).field).isEqualTo(ComposeField.TO)
        onNodeWithText("Queue message").performClick()
        assertThat(event).isEqualTo(ComposerEvent.Queue)
        onNodeWithText("Send now").assertDoesNotExist()
    }

    @Test
    fun `uncertain outcome shows duplicate risk before confirmation action`() = runComposeTest {
        var event: ComposerEvent? = null
        val state = ComposerState(
            emptyList(),
            account = AccountIdFactory.create(),
            selected = draft(OutgoingState.UNCERTAIN),
            confirmRetry = true,
        )
        setContent { ThunderbirdBoltTheme { ComposerScreen(state, { event = it }, null) } }
        onNodeWithText(
            "Sending again may deliver a duplicate. Do you want to send another attempt?",
        ).assertIsDisplayed()
        onNodeWithText("Keep uncertain").performClick()
        assertThat(event).isEqualTo(ComposerEvent.CancelRetry)
        onNodeWithText("Send again despite duplicate risk").performClick()
        assertThat(event).isEqualTo(ComposerEvent.ConfirmRetry)
        onNodeWithText("Send now").assertDoesNotExist()
    }

    private fun draft(state: OutgoingState) = OutgoingMail(
        "fixture",
        "recipient@example.invalid",
        "Fixture",
        "Body",
        "<fixture@example.invalid>",
        state,
    )
}
