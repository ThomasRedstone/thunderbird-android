package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import org.junit.Test

class DemoMailScreenTest : ComposeUiTestHarness() {
    @Test
    fun `open inbox filter subjects read and navigate back`() = runComposeTest {
        val message = DemoMessage("welcome", "Welcome", "Demo sender", "A sample plain-text message")
        val folder = DemoFolder("inbox", "Inbox", listOf(message))
        val testSubject = DemoMailViewModel(listOf(folder, DemoFolder("empty", "Empty", emptyList())))
        setContent { ThunderbirdBoltTheme { DemoMailScreen(testSubject) } }

        onNodeWithText("Inbox (1)").performClick()
        onNodeWithText("Filter subjects").performTextInput("Welcome")
        onNodeWithText("Welcome\nDemo sender").performClick()
        onNodeWithText("A sample plain-text message").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithText("Welcome\nDemo sender").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithText("Empty (0)").performClick()
        onNodeWithText("No messages").assertIsDisplayed()
    }
}
