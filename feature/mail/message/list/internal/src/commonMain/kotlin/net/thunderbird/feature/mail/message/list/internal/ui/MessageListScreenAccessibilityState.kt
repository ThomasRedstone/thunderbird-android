package net.thunderbird.feature.mail.message.list.internal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import net.thunderbird.components.ui.bolt.molecule.swipe.SwipeDirectionAccessibilityAction
import net.thunderbird.components.ui.bolt.molecule.swipe.SwipeDirectionAccessibilityAction.EndToStartAccessibilityAction
import net.thunderbird.components.ui.bolt.molecule.swipe.SwipeDirectionAccessibilityAction.StartToEndAccessibilityAction
import net.thunderbird.core.common.action.SwipeAction
import net.thunderbird.core.common.action.SwipeActions
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi

/**
 * Manages accessibility state and descriptions for messages in a message list screen.
 *
 * This class provides accessibility support for message list items by managing state descriptions
 * and swipe gesture accessibility actions. It maps different message states (combining read status,
 * selection state, and active state) to human-readable descriptions that can be announced by
 * accessibility services.
 *
 * @param stateDescription A map that associates each [MessageListStateDescription] enum value
 *  with its corresponding localized accessibility description string.
 * @param swipeDirectionAccessibilityAction An immutable list of accessibility actions for
 *  swipe gestures that can be performed on message items. Defaults to an empty list if no
 *  swipe actions are available.
 */
@Stable
internal class MessageListScreenAccessibilityState(
    private val stateDescription: Map<MessageListStateDescription, String>,
    val swipeDirectionAccessibilityAction: ImmutableList<SwipeDirectionAccessibilityAction> = persistentListOf(),
) {
    /**
     * Retrieves the state description for accessibility purposes for the given message.
     *
     * @param message The message item for which to retrieve the state description.
     * @return A string containing the accessibility state description for the message.
     * @throws NoSuchElementException if the message does not have a corresponding state description.
     */
    @Throws(NoSuchElementException::class)
    fun stateDescription(message: MessageItemUi): String =
        stateDescription.getValue(message.toMessageStateDescription())

    private fun MessageItemUi.toMessageStateDescription(): MessageListStateDescription = when (state) {
        MessageItemUi.State.New if active -> MessageListStateDescription.ActiveNewMessage
        MessageItemUi.State.Read if active -> MessageListStateDescription.ActiveReadMessage
        MessageItemUi.State.Unread if active -> MessageListStateDescription.ActiveUnreadMessage
        MessageItemUi.State.New if selected -> MessageListStateDescription.SelectedNewMessage
        MessageItemUi.State.Read if selected -> MessageListStateDescription.SelectedReadMessage
        MessageItemUi.State.Unread if selected -> MessageListStateDescription.SelectedUnreadMessage
        MessageItemUi.State.New -> MessageListStateDescription.NewMessage
        MessageItemUi.State.Read -> MessageListStateDescription.ReadMessage
        MessageItemUi.State.Unread -> MessageListStateDescription.UnreadMessage
    }
}

/**
 * Describes the various states a message can be in within a message list, combining read status,
 * selection state, and active state.
 *
 * This enumeration represents all possible combinations of three orthogonal message properties:
 * - Read status: New (never opened), Read (opened), or Unread (marked as unread)
 * - Selection state: Not selected, Selected (marked for batch operations)
 * - Active state: Not active or Active (currently focused/displayed)
 *
 * The state descriptions are used to determine the appropriate visual presentation and
 * interaction behavior for messages in the message list UI.
 */
enum class MessageListStateDescription {
    NewMessage,
    ReadMessage,
    UnreadMessage,
    ActiveNewMessage,
    ActiveReadMessage,
    ActiveUnreadMessage,
    SelectedNewMessage,
    SelectedReadMessage,
    SelectedUnreadMessage,
}

/**
 * Creates and remembers a [MessageListScreenAccessibilityState] configured for the message list screen.
 *
 * When swipe actions are provided, they are converted into accessibility actions that can be
 * triggered programmatically, allowing users with accessibility needs to perform swipe-based
 * operations without physical gestures.
 *
 * @param swipeActions The swipe action configuration containing left and right swipe actions
 *  that should be made available as accessibility actions. If null or if individual actions are
 *  [SwipeAction.None], no corresponding accessibility actions will be created for those directions.
 * @return A remembered [MessageListScreenAccessibilityState] instance containing state descriptions
 *  and accessibility actions for swipe gestures. The instance is only recreated when the state
 *  description strings or swipe actions change.
 */
@Composable
internal fun rememberMessageListScreenAccessibilityState(
    swipeActions: SwipeActions?,
): MessageListScreenAccessibilityState {
    val stateDescription = messageListStateDescriptions()
    val rightActionLabel = swipeActions?.rightAction?.let { swipeActionLabel(it, toggled = false) }
    val leftActionLabel = swipeActions?.leftAction?.let { swipeActionLabel(it, toggled = false) }
    val swipeDirectionAccessibilityAction = remember(rightActionLabel, leftActionLabel) {
        buildList {
            if (rightActionLabel != null) {
                add(StartToEndAccessibilityAction(actionLabel = { rightActionLabel }))
            }
            if (leftActionLabel != null) {
                add(EndToStartAccessibilityAction(actionLabel = { leftActionLabel }))
            }
        }
    }

    return remember(stateDescription, swipeDirectionAccessibilityAction) {
        MessageListScreenAccessibilityState(stateDescription, swipeDirectionAccessibilityAction.toPersistentList())
    }
}
