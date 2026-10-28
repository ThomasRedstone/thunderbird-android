package net.thunderbird.feature.mail.message.list.internal.ui

import androidx.compose.runtime.Composable
import net.thunderbird.core.common.action.SwipeAction

/**
 * Localised accessibility descriptions for every [MessageListStateDescription].
 */
@Composable
internal expect fun messageListStateDescriptions(): Map<MessageListStateDescription, String>

/**
 * Localised label of a [SwipeAction], or `null` when the action has no label (for example [SwipeAction.None]).
 *
 * @param toggled Whether the action is in its toggled state (e.g. "Mark unread" instead of "Mark read").
 */
@Composable
internal expect fun swipeActionLabel(action: SwipeAction, toggled: Boolean): String?
