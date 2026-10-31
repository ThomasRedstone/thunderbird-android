package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailNavigationDrawerTest : ComposeUiTestHarness() {
    @Test
    fun `drawer opens a folder from the selected cached account`() = runComposeTest {
        val account = fixtureAccount()
        val folder = MailFolder("INBOX", "Inbox")
        val state = LiveMailState(accounts = listOf(account), account = account.id, folders = listOf(folder))
        var selected: LiveMailEvent? = null

        setContent {
            ThunderbirdBoltTheme {
                MailNavigationDrawer(state, { selected = it }, {}, {}) { openDrawer ->
                    ButtonText("Menu", openDrawer)
                }
            }
        }

        onNodeWithText("Menu").performClick()
        onNodeWithText("Inbox").performClick()

        assertThat(selected).isEqualTo(LiveMailEvent.OpenFolder(folder))
    }

    private fun fixtureAccount(): UtMailAccount {
        val settings = ServerSettings(
            "imap",
            "localhost",
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        return UtMailAccount(
            AccountIdFactory.create(),
            "Fixture account",
            "fixture@example.invalid",
            settings,
            settings.copy(type = "smtp", port = 465),
            UUID.randomUUID().toString(),
        )
    }
}
