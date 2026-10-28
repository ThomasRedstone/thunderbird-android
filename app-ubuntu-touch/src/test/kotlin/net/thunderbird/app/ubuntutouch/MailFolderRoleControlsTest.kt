package net.thunderbird.app.ubuntutouch

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

class MailFolderRoleControlsTest : ComposeUiTestHarness() {
    @Test
    fun `queue requires configured role target and selecting a folder only offers configuration`() = runComposeTest {
        val key = MailMessageKey(AccountIdFactory.create(), "INBOX", "1")
        val current = mutableStateOf(
            MailMoveUiState(
                request = key,
                role = MailFolderRole.TRASH,
                target = "Archive",
                overview = MoveOverview(
                    MoveSource(key, "Fixture", true),
                    listOf(MoveDestination(MailFolder("Archive", "Archive"), true)),
                ),
            ),
        )
        val events = mutableListOf<MailMoveEvent>()
        setContent { ThunderbirdBoltTheme { MailMoveScreen(current.value, events::add) } }
        onNodeWithText("Queue move").assertIsNotEnabled()
        onNodeWithText("Use selected folder for Trash").performClick()
        assertThat(events).isEqualTo(listOf(MailMoveEvent.ConfigureRole))
        current.value = current.value.copy(
            overview = current.value.overview.copy(
                roles = mapOf(MailFolderRole.TRASH to "Archive"),
            ),
        )
        onNodeWithText("Queue move").assertIsEnabled()
        onNodeWithText("Clear Trash destination").performClick()
        assertThat(events.last()).isEqualTo(MailMoveEvent.ClearRole)
    }

    @Test
    fun `reader archive and trash preserve exact message context and busy disables actions`() = runComposeTest {
        val account = AccountIdFactory.create()
        val key = MailMessageKey(account, "Archive", "42")
        val current = mutableStateOf(
            LiveMailState(
                emptyList(),
                account = account,
                folder = MailFolder("Archive", "Archive"),
                content = MailContent(MailSummary("42", "Fixture", "Sender", false), "Preview", false),
            ),
        )
        val events = mutableListOf<MailMoveEvent.Open>()
        setContent {
            ThunderbirdBoltTheme {
                androidx.compose.foundation.layout.Row {
                    ReaderFolderRoleActions(current.value, events::add)
                }
            }
        }
        onNodeWithText("Archive").performClick()
        onNodeWithText("Move to Trash").performClick()
        assertThat(
            events,
        ).isEqualTo(
            listOf(MailMoveEvent.Open(key, MailFolderRole.ARCHIVE), MailMoveEvent.Open(key, MailFolderRole.TRASH)),
        )
        current.value = current.value.copy(busy = true)
        onNodeWithText("Archive").assertIsNotEnabled()
        onNodeWithText("Move to Trash").assertIsNotEnabled()
    }
}
