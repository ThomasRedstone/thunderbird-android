package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailSortScreenTest : ComposeUiTestHarness() {
    @Test
    fun `sort control shows current order and requests next order`() = runComposeTest {
        val events = mutableListOf<LiveMailEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state(), onEvent = events::add) }
            }
        }
        onNodeWithText("Sort: Newest first").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(listOf(LiveMailEvent.Sort(MailSort.OLDEST)))
    }

    @Test
    fun `sort control is disabled during cache operation`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state().copy(busy = true), onEvent = {}) }
            }
        }
        onNodeWithText("Sort: Newest first").assertIsNotEnabled()
    }

    @Test
    fun `changing sort starts at first result instead of retaining the old visible UID`() = runComposeTest {
        var current by mutableStateOf(
            state().copy(
                messages = (0 until 50).map {
                    MailSummary(it.toString(), "Item $it", "Sender", false, effectiveDate = (50 - it).toLong())
                },
            ),
        )
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    LiveMailScreen(current, onEvent = { event ->
                        if (event is LiveMailEvent.Sort) {
                            current = current.copy(sort = event.sort, messages = current.messages.reversed())
                        }
                    })
                }
            }
        }
        onNodeWithTag("cached-message-list").performScrollToIndex(30)
        onNodeWithText("Sort: Newest first").performClick()
        waitForIdle()
        onNodeWithText("Item 49\nSender").assertIsDisplayed()
    }

    private fun state() = LiveMailState(
        emptyList(),
        account = AccountIdFactory.create(),
        folder = MailFolder("INBOX", "Inbox"),
    )
}
