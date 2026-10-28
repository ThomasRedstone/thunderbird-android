package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailCopyControlsTest : ComposeUiTestHarness() {
    @Test
    fun `repeated copy has explicit review and busy disables confirmation`() = runComposeTest {
        val key = MailMessageKey(AccountIdFactory.create(), "INBOX", "1")
        val prior = MailMove(
            "prior", key.account, key.folder, key.uid, "Archive", 1, 2,
            MailMoveState.COMPLETE, 1, false, "binding", MailMoveKind.COPY,
        )
        val current = mutableStateOf(
            MailMoveUiState(
                request = key,
                target = "Archive",
                kind = MailMoveKind.COPY,
                overview = MoveOverview(
                    MoveSource(key, "Fixture", true, 1),
                    listOf(MoveDestination(MailFolder("Archive", "Archive"), true, 2)),
                    listOf(MoveView(prior, null, true)),
                ),
            ),
        )
        val events = mutableListOf<MailMoveEvent>()
        setContent { ThunderbirdBoltTheme { Column { FilingQueueControls(current.value, events::add) } } }
        onNodeWithText("Queue copy").assertDoesNotExist()
        onNodeWithText("Queue another copy").assertDoesNotExist()
        onNodeWithText("Review another copy").performClick()
        assertThat(events).isEqualTo(listOf(MailMoveEvent.ReviewCopyAgain))
        current.value = current.value.copy(confirmCopyAgain = true)
        onNodeWithText("Queue another copy").performClick()
        assertThat(events.last()).isEqualTo(MailMoveEvent.ConfirmCopyAgain)
        current.value = current.value.copy(busy = true)
        onNodeWithText("Queue another copy").assertIsNotEnabled()
        current.value = current.value.copy(
            busy = false,
            overview = current.value.overview.copy(
                destinations = listOf(
                    MoveDestination(MailFolder("Archive", "Archive"), true, 3),
                ),
            ),
        )
        onNodeWithText("Queue copy").assertIsEnabled()
        onNodeWithText("Queue another copy").assertDoesNotExist()
    }
}
