package net.thunderbird.feature.mail.message.list.internal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlin.test.Test
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.components.ui.testing.ComposeUiTestScope
import net.thunderbird.core.common.action.SwipeAction
import net.thunderbird.core.common.action.SwipeActions
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListDateTimeFormat
import net.thunderbird.core.preference.display.visualSettings.message.list.UiDensity
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.message.list.internal.ui.component.template.TEST_TAG_MESSAGE_LIST_ROOT
import net.thunderbird.feature.mail.message.list.preferences.MessageListPreferences
import net.thunderbird.feature.mail.message.list.ui.component.atom.MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG
import net.thunderbird.feature.mail.message.list.ui.component.rememberMessageListScope
import net.thunderbird.feature.mail.message.list.ui.event.MessageItemEvent
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import net.thunderbird.feature.mail.message.list.ui.state.Account
import net.thunderbird.feature.mail.message.list.ui.state.Avatar
import net.thunderbird.feature.mail.message.list.ui.state.ComposedAddressUi
import net.thunderbird.feature.mail.message.list.ui.state.Folder
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi
import net.thunderbird.feature.mail.message.list.ui.state.MessageListFooter
import net.thunderbird.feature.mail.message.list.ui.state.MessageListMetadata
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState

class MessageListScreenRendererJvmTest : ComposeUiTestHarness() {
    private val account = Account(AccountIdFactory.create(), Color.Blue)

    @Test
    fun `renders the root and every subject`() = runComposeTest {
        val messages = sampleMessages()
        setContent(state(messages), mutableListOf())

        onNodeWithTag(TEST_TAG_MESSAGE_LIST_ROOT).assertIsDisplayed()
        messages.forEach { onNodeWithText(it.subject).assertIsDisplayed() }
    }

    @Test
    fun `clicking a row dispatches OnMessageClick`() = runComposeTest {
        val message = sampleMessages().first()
        val events = mutableListOf<MessageListEvent>()
        setContent(state(listOf(message)), events)

        onNodeWithText(message.subject).performClick()
        waitForIdle()

        assertThat(events).containsExactly(MessageItemEvent.OnMessageClick(message))
    }

    @Test
    fun `clicking the favourite button dispatches ToggleFavourite`() = runComposeTest {
        val message = sampleMessages().first()
        val events = mutableListOf<MessageListEvent>()
        setContent(state(listOf(message)), events)

        onNodeWithTag(MESSAGE_ITEM_FAVOURITE_ICON_BUTTON_TEST_TAG).performClick()
        waitForIdle()

        assertThat(events).containsExactly(MessageItemEvent.ToggleFavourite(message))
    }

    @Test
    fun `clicking the avatar dispatches ToggleSelectMessages`() = runComposeTest {
        val message = sampleMessages().first()
        val events = mutableListOf<MessageListEvent>()
        setContent(state(listOf(message)), events)

        onNodeWithText(AVATAR_MONOGRAM, useUnmergedTree = true).performClick()
        waitForIdle()

        assertThat(events).containsExactly(MessageItemEvent.ToggleSelectMessages(message))
    }

    @Test
    fun `rows expose merged state descriptions`() = runComposeTest {
        val messages = sampleMessages()
        setContent(state(messages), mutableListOf())

        assertThat(stateDescriptionOf(messages[0])).isEqualTo("Read")
        assertThat(stateDescriptionOf(messages[1])).isEqualTo("Unread")
        assertThat(stateDescriptionOf(messages[2])).isEqualTo("Selected, Read")
    }

    @Test
    fun `swipe accessibility actions are labelled and dispatch OnSwipeMessage`() = runComposeTest {
        val message = sampleMessages().first()
        val events = mutableListOf<MessageListEvent>()
        val swipeActions = SwipeActions(leftAction = SwipeAction.Delete, rightAction = SwipeAction.Archive)
        setContent(state(listOf(message), swipeActions), events)

        var node: SemanticsNode? = onNodeWithText(message.subject).fetchSemanticsNode()
        while (node != null && node.config.getOrNull(SemanticsActions.CustomActions) == null) {
            node = node.parent
        }
        val customActions = checkNotNull(node).config[SemanticsActions.CustomActions]
        assertThat(customActions.map { it.label }).containsExactly("Right: Archive", "Left: Delete")
        val action = customActions.firstOrNull { it.label == "Right: Archive" }
        assertThat(action).isNotNull()
        action!!.action()
        waitForIdle()

        assertThat(events).containsExactly(MessageItemEvent.OnSwipeMessage(message, SwipeAction.Archive))
    }

    @Test
    fun `footer is shown only with text and dispatches OnFooterClick`() = runComposeTest {
        val message = sampleMessages().first()
        val events = mutableListOf<MessageListEvent>()
        val withoutFooter = state(listOf(message))
        setContent(withoutFooter, events)
        onNodeWithText(FOOTER_TEXT).assertDoesNotExist()

        val withFooter = state(listOf(message), footer = MessageListFooter(text = FOOTER_TEXT))
        setContent(withFooter, events)
        onNodeWithText(FOOTER_TEXT).performClick()
        waitForIdle()

        assertThat(events).containsExactly(MessageListEvent.OnFooterClick)
    }

    private fun ComposeUiTestScope.setContent(
        state: MessageListState,
        events: MutableList<MessageListEvent>,
    ) {
        setContent {
            ThunderbirdBoltTheme {
                with(MessageListScreenRenderer()) {
                    rememberMessageListScope().Render(
                        state = state,
                        dispatchEvent = { event ->
                            // Focus changes are a side effect of pointer input on JVM, not under test here.
                            if (event !is MessageItemEvent.OnFocusEnter && event !is MessageItemEvent.OnFocusExit) {
                                events.add(event)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun ComposeUiTestScope.stateDescriptionOf(message: MessageItemUi): String? =
        onNodeWithText(message.subject).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun sampleMessages() = listOf(
        message("read-1", "Read subject", MessageItemUi.State.Read),
        message("unread-1", "Unread subject", MessageItemUi.State.Unread, starred = true),
        message("selected-1", "Selected subject", MessageItemUi.State.Read, selected = true),
    )

    private fun message(
        id: String,
        subject: String,
        state: MessageItemUi.State,
        starred: Boolean = false,
        selected: Boolean = false,
    ) = MessageItemUi(
        state = state,
        id = id,
        messageReference = "reference-$id",
        account = account,
        senders = ComposedAddressUi("Sender $id", avatar = Avatar.Monogram(AVATAR_MONOGRAM)),
        subject = subject,
        excerpt = "Excerpt $id",
        formattedReceivedAt = "12:34",
        hasAttachments = false,
        starred = starred,
        encrypted = false,
        answered = false,
        forwarded = false,
        selected = selected,
    )

    private fun state(
        messages: List<MessageItemUi>,
        swipeActions: SwipeActions? = null,
        footer: MessageListFooter = MessageListFooter(),
    ) = MessageListState.LoadedMessages(
        metadata = MessageListMetadata(
            folder = Folder(id = "folder-inbox", account = account, name = "Inbox", type = FolderType.INBOX),
            swipeActions = swipeActions?.let { persistentMapOf(account.id to it) } ?: persistentMapOf(),
            sortCriteriaPerAccount = persistentMapOf(),
            isActive = false,
            footer = footer,
        ),
        preferences = MessageListPreferences(
            density = UiDensity.Default,
            groupConversations = false,
            showCorrespondentNames = true,
            showMessageAvatar = true,
            showFavouriteButton = true,
            senderAboveSubject = true,
            excerptLines = 1,
            dateTimeFormat = MessageListDateTimeFormat.Contextual,
        ),
        messages = messages.toImmutableList(),
    )

    private companion object {
        const val AVATAR_MONOGRAM = "S"
        const val FOOTER_TEXT = "Load more messages"
    }
}
