package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailMoveScreenTest : ComposeUiTestHarness() {
    @Test
    fun `reader copy request keeps the actual message context and COPY kind`() = runComposeTest {
        val account = AccountIdFactory.create()
        val key = MailMessageKey(account, "Archive", "42")
        val state = LiveMailState(
            emptyList(),
            account = account,
            folder = MailFolder("Archive", "Archive"),
            content = MailContent(MailSummary("42", "Fixture", "Sender", false), "Preview", false),
        )
        val events = mutableListOf<MailMoveEvent.Open>()
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state, onMove = events::add, onEvent = {}) } }
        onNodeWithText("Copy message").performScrollTo().performClick()
        assertThat(events).isEqualTo(listOf(MailMoveEvent.Open(key, kind = MailMoveKind.COPY)))
    }

    @Test
    fun `queue requires verified source and synced destination`() = runComposeTest {
        val key = MailMessageKey(AccountIdFactory.create(), "INBOX", "1")
        val initial = MoveOverview(
            MoveSource(key, "Fixture", false),
            listOf(MoveDestination(MailFolder("Archive", "Archive"), true)),
        )
        val current = mutableStateOf(MailMoveUiState(overview = initial, request = key, target = "Archive"))
        val events = mutableListOf<MailMoveEvent>()
        setContent { ThunderbirdBoltTheme { MailMoveScreen(current.value, events::add) } }
        onNodeWithText("Queue move").assertIsNotEnabled()
        onNodeWithText("Verify source message").performClick()
        assertThat(events).isEqualTo(listOf(MailMoveEvent.Verify))
        current.value =
            current.value.copy(overview = initial.copy(source = checkNotNull(initial.source).copy(verified = true)))
        onNodeWithText("Queue move").assertIsEnabled().performClick()
        assertThat(events.last()).isEqualTo(MailMoveEvent.Queue)
        onNodeWithText("Move now").assertDoesNotExist()
    }

    @Test
    fun `uncertain move retry requires review and remains disabled while busy`() = runComposeTest {
        val move = MailMove(
            "fixture", AccountIdFactory.create(), "INBOX", "1", "Archive", 1, 2,
            MailMoveState.UNKNOWN, 1, true, "binding",
        )
        val overview =
            MoveOverview(history = listOf(MoveView(move, MailMovePayloadInfo(7, true, null), true, "Fixture")))
        val current = mutableStateOf(MailMoveUiState(overview = overview, selected = move))
        val events = mutableListOf<MailMoveEvent>()
        setContent { ThunderbirdBoltTheme { MailMoveScreen(current.value, events::add) } }
        onNodeWithText("Retry move despite risk").assertDoesNotExist()
        onNodeWithText("Review retry risk").performScrollTo().performClick()
        assertThat(events).isEqualTo(listOf(MailMoveEvent.ReviewRetry))
        current.value = current.value.copy(confirmRetry = true)
        onNodeWithText("Retry move despite risk").performScrollTo().performClick()
        assertThat(events.last()).isEqualTo(MailMoveEvent.ConfirmRetry)
        current.value = current.value.copy(busy = true)
        onNodeWithText("Retry move despite risk").assertIsNotEnabled()
    }

    @Test
    fun `reader sends account folder and uid for preview messages without submitting a move`() = runComposeTest {
        val account = AccountIdFactory.create()
        val state = LiveMailState(
            emptyList(),
            account = account,
            folder = MailFolder("Archive", "Archive"),
            content = MailContent(MailSummary("42", "Fixture", "Sender", false), "Preview", false),
        )
        val events = mutableListOf<MailMoveEvent.Open>()
        setContent { ThunderbirdBoltTheme { LiveMailScreen(state, onMove = events::add, onEvent = {}) } }
        onNodeWithText("Move message").performScrollTo().performClick()
        assertThat(events).isEqualTo(listOf(MailMoveEvent.Open(MailMessageKey(account, "Archive", "42"))))
    }
}
