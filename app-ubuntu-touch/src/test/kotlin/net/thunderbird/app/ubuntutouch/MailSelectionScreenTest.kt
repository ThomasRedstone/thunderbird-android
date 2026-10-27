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
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailSelectionScreenTest : ComposeUiTestHarness() {
    private val account = AccountIdFactory.create()
    private val key = MailMessageKey(account, "INBOX", "1")

    @Test
    fun `selected row toggles selection and bulk control requests explicit assignment`() = runComposeTest {
        val events = mutableListOf<LiveMailEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state(), onEvent = events::add) }
            }
        }
        onNodeWithText("Selected · Subject\nSender").performClick()
        onNodeWithText("Mark read").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(
            listOf(
                LiveMailEvent.Selection(MailSelectionAction.Toggle(key)),
                LiveMailEvent.Selection(MailSelectionAction.ApplyFlag(Flag.SEEN, true)),
            ),
        )
    }

    @Test
    fun `bulk controls and selected rows are disabled while local write is busy`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state().copy(busy = true), onEvent = {}) }
            }
        }
        onNodeWithText("Mark read").assertIsNotEnabled()
        onNodeWithText("Cancel selection").assertIsNotEnabled()
        onNodeWithText("Selected · Subject\nSender").assertIsNotEnabled()
    }

    @Test
    fun `empty selection cannot apply bulk flags`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state().copy(selection = MailSelection(true)), onEvent = {}) }
            }
        }
        onNodeWithText("Mark read").assertIsNotEnabled()
        onNodeWithText("Star").assertIsNotEnabled()
    }

    @Test
    fun `unified queue review directs to account rather than offering origin only discard`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    LiveMailScreen(
                        state().copy(
                            searchScope = CachedSearchScope.ALL_ACCOUNTS,
                            pendingActions = 4,
                            confirmDiscard = true,
                        ),
                        onEvent = {},
                    )
                }
            }
        }
        onNodeWithText("Open an account to review or discard its local changes.").assertIsDisplayed()
        onNodeWithText("Discard changes").assertDoesNotExist()
        onNodeWithText("Review local changes").assertDoesNotExist()
    }

    @Test
    fun `queued change sync button requests foreground replay`() = runComposeTest {
        val events = mutableListOf<LiveMailEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state().copy(pendingActions = 1), onEvent = events::add) }
            }
        }
        onNodeWithText("Sync queued changes").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(listOf(LiveMailEvent.SyncQueuedFlags))
    }

    @Test
    fun `queued change sync button disables while operation is running`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(state().copy(pendingActions = 1, busy = true), onEvent = {}) }
            }
        }
        onNodeWithText("Sync queued changes").assertIsNotEnabled()
    }

    private fun state() = LiveMailState(
        emptyList(),
        account,
        folder = MailFolder("INBOX", "Inbox"),
        messages = listOf(MailSummary("1", "Subject", "Sender", false)),
        selection = MailSelection(true, setOf(key)),
    )
}
