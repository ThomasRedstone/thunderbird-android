package net.thunderbird.feature.mail.message.list.internal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.thunderbird.core.common.action.SwipeAction
import net.thunderbird.feature.mail.message.list.internal.R
import net.thunderbird.feature.mail.message.list.R as ApiR

@Composable
internal actual fun messageListStateDescriptions(): Map<MessageListStateDescription, String> = mapOf(
    MessageListStateDescription.NewMessage to stringResource(
        id = R.string.message_list_state_new_message_description,
    ),
    MessageListStateDescription.ReadMessage to stringResource(
        id = R.string.message_list_state_read_message_description,
    ),
    MessageListStateDescription.UnreadMessage to stringResource(
        id = R.string.message_list_state_unread_message_description,
    ),
    MessageListStateDescription.ActiveNewMessage to stringResource(
        id = R.string.message_list_state_active_new_message_description,
    ),
    MessageListStateDescription.ActiveReadMessage to stringResource(
        id = R.string.message_list_state_active_read_message_description,
    ),
    MessageListStateDescription.ActiveUnreadMessage to stringResource(
        id = R.string.message_list_state_active_unread_message_description,
    ),
    MessageListStateDescription.SelectedNewMessage to stringResource(
        id = R.string.message_list_state_selected_new_message_description,
    ),
    MessageListStateDescription.SelectedReadMessage to stringResource(
        id = R.string.message_list_state_selected_read_message_description,
    ),
    MessageListStateDescription.SelectedUnreadMessage to stringResource(
        id = R.string.message_list_state_selected_unread_message_description,
    ),
)

@Composable
internal actual fun swipeActionLabel(action: SwipeAction, toggled: Boolean): String? =
    action.labelRes(toggled)?.let { stringResource(it) }

private fun SwipeAction.labelRes(toggled: Boolean): Int? = when (this) {
    SwipeAction.None -> null
    SwipeAction.ToggleSelection if toggled -> ApiR.string.swipe_action_deselect
    SwipeAction.ToggleSelection -> ApiR.string.swipe_action_select
    SwipeAction.ToggleRead if toggled -> ApiR.string.swipe_action_mark_as_unread
    SwipeAction.ToggleRead -> ApiR.string.swipe_action_mark_as_read
    SwipeAction.ToggleStar if toggled -> ApiR.string.swipe_action_remove_star
    SwipeAction.ToggleStar -> ApiR.string.swipe_action_add_star
    SwipeAction.Archive -> ApiR.string.swipe_action_archive
    SwipeAction.ArchiveSetupArchiveFolder -> ApiR.string.swipe_action_archive_folder_not_set
    SwipeAction.ArchiveDisabled -> ApiR.string.swipe_action_change_swipe_gestures
    SwipeAction.Delete -> ApiR.string.swipe_action_delete
    SwipeAction.Spam -> ApiR.string.swipe_action_spam
    SwipeAction.Move -> ApiR.string.swipe_action_move
}
