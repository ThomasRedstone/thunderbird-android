package net.thunderbird.feature.mail.message.list.ui.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListDateTimeFormat
import net.thunderbird.core.preference.display.visualSettings.message.list.UiDensity
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.list.preferences.MessageListPreferences
import net.thunderbird.feature.mail.message.list.ui.component.atom.MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG
import net.thunderbird.feature.mail.message.list.ui.component.organism.ReadMessageItem
import net.thunderbird.feature.mail.message.list.ui.component.organism.UnreadMessageItem
import net.thunderbird.feature.mail.message.list.ui.state.Account
import net.thunderbird.feature.mail.message.list.ui.state.ComposedAddressUi
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi

class PortableMessageItemTest : ComposeUiTestHarness() {
    @Test
    fun `canonical read mobile row renders subject and dispatches open`() = runComposeTest {
        var opened = 0
        val state = message(MessageItemUi.State.Read)
        setContent {
            ThunderbirdBoltTheme {
                ReadMessageItem(
                    state,
                    preferences(),
                    null,
                    { opened++ },
                    {},
                    {},
                    {},
                    Modifier.testTag("mobile-row"),
                )
            }
        }
        onNodeWithText("Controlled subject", substring = true).assertExists()
        onNodeWithTag("mobile-row").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `canonical unread mobile row keeps favourite and selection interactions`() = runComposeTest {
        val state = mutableStateOf(message(MessageItemUi.State.Unread))
        setContent {
            ThunderbirdBoltTheme {
                UnreadMessageItem(
                    state.value,
                    preferences(),
                    null,
                    {},
                    { state.value = state.value.copy(selected = true) },
                    {},
                    { state.value = state.value.copy(starred = it) },
                    Modifier.testTag("mobile-row"),
                )
            }
        }
        onNodeWithTag(MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG).performClick()
        assertThat(state.value.starred).isTrue()
        onNodeWithTag(MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG).performClick()
        assertThat(state.value.starred).isFalse()
        onNodeWithTag("mobile-row").performTouchInput { longClick() }
        assertThat(state.value.selected).isTrue()
        onNodeWithText("Controlled subject", substring = true).assertExists()
    }

    @Test
    fun `canonical read mobile row emits both favourite changes`() = runComposeTest {
        val state = mutableStateOf(message(MessageItemUi.State.Read))
        setContent {
            ThunderbirdBoltTheme {
                ReadMessageItem(
                    state.value,
                    preferences(),
                    null,
                    {},
                    {},
                    {},
                    { state.value = state.value.copy(starred = it) },
                    Modifier.testTag("mobile-row"),
                )
            }
        }
        onNodeWithTag(MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG).performClick()
        assertThat(state.value.starred).isTrue()
        onNodeWithTag(MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG).performClick()
        assertThat(state.value.starred).isFalse()
    }

    private fun message(state: MessageItemUi.State) = MessageItemUi(
        state = state,
        id = "fixture-1",
        messageReference = "controlled-reference",
        account = Account(AccountIdFactory.create(), Color.Blue),
        senders = ComposedAddressUi("Controlled sender"),
        subject = "Controlled subject",
        excerpt = "Controlled preview",
        formattedReceivedAt = "12:34",
        hasAttachments = false,
        starred = false,
        encrypted = false,
        answered = false,
        forwarded = false,
        selected = false,
    )

    private fun preferences() = MessageListPreferences(
        density = UiDensity.Default,
        groupConversations = false,
        showCorrespondentNames = true,
        showMessageAvatar = false,
        showFavouriteButton = true,
        senderAboveSubject = true,
        excerptLines = 1,
        dateTimeFormat = MessageListDateTimeFormat.Contextual,
    )
}
