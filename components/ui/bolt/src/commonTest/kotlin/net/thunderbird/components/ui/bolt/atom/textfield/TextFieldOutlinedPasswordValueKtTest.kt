package net.thunderbird.components.ui.bolt.atom.textfield

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import org.junit.Test

class TextFieldOutlinedPasswordValueKtTest : ComposeUiTestHarness() {
    @Test
    fun `password value preserves cursor selection for replacement`() = runComposeTest {
        val value = mutableStateOf(TextFieldValue("abcd"))
        setContent {
            TextFieldOutlinedPassword(value.value, { value.value = it }, Modifier.testTag("password"))
        }
        onNodeWithTag("password").performClick()
        onNodeWithTag("password").performTextInputSelection(TextRange(1, 3))
        onNodeWithTag("password").performTextInput("X")
        assertThat(value.value.text).isEqualTo("aXd")
        assertThat(value.value.selection).isEqualTo(TextRange(2))
    }
}
