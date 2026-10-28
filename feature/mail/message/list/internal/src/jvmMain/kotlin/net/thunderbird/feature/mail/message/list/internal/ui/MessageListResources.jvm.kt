package net.thunderbird.feature.mail.message.list.internal.ui

import androidx.compose.runtime.Composable
import net.thunderbird.core.common.action.SwipeAction

/**
 * JVM strings are the English literals of the Android `values/strings.xml` resources.
 *
 * Localisation on JVM is a follow-up.
 */
@Composable
internal actual fun messageListStateDescriptions(): Map<MessageListStateDescription, String> = mapOf(
    MessageListStateDescription.NewMessage to "New",
    MessageListStateDescription.ReadMessage to "Read",
    MessageListStateDescription.UnreadMessage to "Unread",
    MessageListStateDescription.ActiveNewMessage to "Active, New",
    MessageListStateDescription.ActiveReadMessage to "Active, Read",
    MessageListStateDescription.ActiveUnreadMessage to "Active, Unread",
    MessageListStateDescription.SelectedNewMessage to "Selected, New",
    MessageListStateDescription.SelectedReadMessage to "Selected, Read",
    MessageListStateDescription.SelectedUnreadMessage to "Selected, Unread",
)

@Composable
internal actual fun swipeActionLabel(action: SwipeAction, toggled: Boolean): String? = when (action) {
    SwipeAction.None -> null
    SwipeAction.ToggleSelection if toggled -> "Deselect"
    SwipeAction.ToggleSelection -> "Select"
    SwipeAction.ToggleRead if toggled -> "Mark unread"
    SwipeAction.ToggleRead -> "Mark read"
    SwipeAction.ToggleStar if toggled -> "Remove star"
    SwipeAction.ToggleStar -> "Add star"
    SwipeAction.Archive -> "Archive"
    SwipeAction.ArchiveSetupArchiveFolder -> "Set Up Archive Folder"
    SwipeAction.ArchiveDisabled -> "Change\nSwipe Gestures"
    SwipeAction.Delete -> "Delete"
    SwipeAction.Spam -> "Spam"
    SwipeAction.Move -> "Move…"
}
