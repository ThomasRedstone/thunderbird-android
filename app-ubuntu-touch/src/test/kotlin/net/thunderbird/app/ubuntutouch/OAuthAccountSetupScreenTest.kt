package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import org.junit.Test

class OAuthAccountSetupScreenTest : ComposeUiTestHarness() {
    @Test
    fun `OAuth form hides password and server fields and offers browser sign in`() = runComposeTest {
        val events = mutableListOf<AccountSetupEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    AccountSetupScreen(oauthState(), events::add, null)
                }
            }
        }
        onNodeWithText("IMAP password").assertDoesNotExist()
        onNodeWithText("IMAP hostname").assertDoesNotExist()
        onNodeWithText("SMTP password").assertDoesNotExist()
        onNodeWithText("Sign in with browser").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(listOf(AccountSetupEvent.Save))
    }

    @Test
    fun `pending browser form allows Cancel while sign in and fields are disabled`() = runComposeTest {
        val events = mutableListOf<AccountSetupEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    AccountSetupScreen(oauthState().copy(busy = true, signingIn = true), events::add, null)
                }
            }
        }
        onNodeWithText("Sign in with browser").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(listOf(AccountSetupEvent.Cancel))
    }

    @Test
    fun `cancelling form disables repeated cancellation`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    AccountSetupScreen(oauthState().copy(busy = true, signingIn = true, cancelling = true), {}, null)
                }
            }
        }
        onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `missing provider disables sign in but leaves Cancel available`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    AccountSetupScreen(oauthState().copy(providers = emptyList()), {}, null)
                }
            }
        }
        onNodeWithText("Sign-in is currently unavailable for this provider.").assertExists()
        onNodeWithText("Sign in with browser").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun `pending cleanup provides an explicit retry action`() = runComposeTest {
        val events = mutableListOf<AccountSetupEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    AccountSetupScreen(AccountSetupState(emptyList(), cleanupPending = true), events::add, null)
                }
            }
        }
        onNodeWithText("Retry credential cleanup").assertIsEnabled().performClick()
        assertThat(events).isEqualTo(listOf(AccountSetupEvent.RetryCredentialCleanup))
    }

    @Test
    fun `pending cleanup disables repeated retry and account navigation while busy`() = runComposeTest {
        setContent {
            ThunderbirdBoltTheme {
                Surface {
                    AccountSetupScreen(AccountSetupState(emptyList(), cleanupPending = true, busy = true), {}, null)
                }
            }
        }
        onNodeWithText("Retry credential cleanup").assertIsNotEnabled()
        onNodeWithText("Add account").assertIsNotEnabled()
    }

    private fun oauthState() = AccountSetupState(
        emptyList(),
        editing = true,
        providerId = "controlled",
        providers = listOf(OAuthProviderChoice("controlled", "Controlled sign-in")),
    )
}
