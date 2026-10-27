package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import org.junit.Test

class AccountSetupScreenTest : ComposeUiTestHarness() {
    @Test
    fun `scroll form enter masked password and cancel`() = runComposeTest {
        val state = mutableStateOf(AccountSetupState(emptyList(), editing = true))
        var cancelled = false
        setContent {
            ThunderbirdBoltTheme {
                AccountSetupScreen(state.value, { event ->
                    when (event) {
                        is AccountSetupEvent.Change -> state.value = state.value.copy(
                            fields = state.value.fields + (event.field to event.value),
                        )

                        AccountSetupEvent.Cancel -> cancelled = true

                        else -> Unit
                    }
                }, null)
            }
        }
        onNodeWithText("IMAP password").performScrollTo().performTextInput("fixture-secret")
        assertThat(state.value.text(AccountField.IMAP_PASSWORD)).isEqualTo("fixture-secret")
        onNodeWithText("IMAP password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat(14))),
        )
        onNodeWithText("IMAP password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        onNodeWithText("Cancel").performScrollTo().performClick()
        assertThat(cancelled).isTrue()
    }
}
