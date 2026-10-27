package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class SignatureSettingsScreenTest : ComposeUiTestHarness() {
    @Test
    fun `editor sends multiline input preference and explicit save events`() = runComposeTest {
        val current =
            mutableStateOf(SignatureSettingsState(emptyList(), account = AccountIdFactory.create(), dirty = true))
        val events = mutableListOf<SignatureSettingsEvent>()
        setContent { ThunderbirdBoltTheme { SignatureSettingsScreen(current.value, events::add, null) } }
        onNodeWithText("Plain-text signature").performTextInput("Fixture\nTeam")
        assertThat((events.last() as SignatureSettingsEvent.Text).value.text).isEqualTo("Fixture\nTeam")
        onNodeWithText("Signature: off").performScrollTo().performClick()
        assertThat(events.last()).isEqualTo(SignatureSettingsEvent.Enabled)
        onNodeWithText("Save signature").performScrollTo().performClick()
        assertThat(events.last()).isEqualTo(SignatureSettingsEvent.Save)
        current.value = current.value.copy(busy = true)
        onNodeWithText("Save signature").assertIsNotEnabled()
    }

    @Test
    fun `discard control requires explicit review before confirmation`() = runComposeTest {
        val current = mutableStateOf(
            SignatureSettingsState(
                emptyList(),
                account = AccountIdFactory.create(),
                input = TextFieldValue("Unsaved"),
                dirty = true,
            ),
        )
        val events = mutableListOf<SignatureSettingsEvent>()
        setContent { ThunderbirdBoltTheme { SignatureSettingsScreen(current.value, events::add, null) } }
        onNodeWithText("Discard signature edits").assertDoesNotExist()
        onNodeWithText("Review discarding edits").performScrollTo().performClick()
        assertThat(events.last()).isEqualTo(SignatureSettingsEvent.ReviewDiscard)
        current.value = current.value.copy(confirmDiscard = true)
        onNodeWithText("Discard signature edits").performScrollTo().assertIsDisplayed().performClick()
        assertThat(events.last()).isEqualTo(SignatureSettingsEvent.ConfirmDiscard)
    }
}
