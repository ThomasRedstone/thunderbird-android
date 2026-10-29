package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.app.composition.appCompositionModule
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState
import org.junit.Test
import org.koin.dsl.koinApplication

class CanonicalMessageListIntegrationTest : ComposeUiTestHarness() {
    @Test
    fun `reducer-backed mailbox waits for its folder state before showing cached rows`() = runComposeTest {
        val renderer = koinApplication { modules(appCompositionModule) }.koin
            .get<MessageListContract.MessageListScreenRenderer>()
        val state = LiveMailState(
            accounts = emptyList(),
            account = AccountIdFactory.create(),
            folder = MailFolder("INBOX", "Inbox"),
            messages = listOf(MailSummary("uid-1", "Cached subject", "Sender", unread = true)),
        )

        setContent {
            ThunderbirdBoltTheme {
                LiveMailScreen(
                    state,
                    messageListRenderer = renderer,
                    canonicalState = MessageListState.WarmingUp(),
                    onCanonicalEvent = {},
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Cached subject").assertDoesNotExist()
        onNodeWithText("Loading cached messages…").assertIsDisplayed()
    }

    @Test
    fun `app composition renders cached folder with canonical mobile row and opens it`() = runComposeTest {
        val renderer = koinApplication { modules(appCompositionModule) }.koin
            .get<MessageListContract.MessageListScreenRenderer>()
        val state = LiveMailState(
            accounts = emptyList(),
            account = AccountIdFactory.create(),
            folder = MailFolder("INBOX", "Inbox"),
            messages = listOf(MailSummary("uid-1", "Cached subject", "Sender", unread = true)),
        )
        val events = mutableListOf<LiveMailEvent>()

        setContent {
            ThunderbirdBoltTheme {
                LiveMailScreen(
                    state,
                    messageListRenderer = renderer,
                    canonicalState = state.canonicalMessageList(),
                    onCanonicalEvent = { handleCanonicalMessageListEvent(it, state, events::add) },
                    onEvent = events::add,
                )
            }
        }

        onNodeWithText("Cached subject").assertIsDisplayed().performClick()
        waitForIdle()
        assertThat(events).isEqualTo(listOf(LiveMailEvent.OpenMessage("uid-1")))
    }
}
