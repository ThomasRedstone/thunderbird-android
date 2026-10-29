package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.component.rememberMessageListScope
import net.thunderbird.feature.mail.message.list.ui.event.MessageItemEvent
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState

@Composable
internal fun CanonicalMessageListScreen(
    canonical: MessageListState,
    dispatchEvent: (MessageListEvent) -> Unit,
    renderer: MessageListContract.MessageListScreenRenderer,
    modifier: Modifier = Modifier,
) {
    with(renderer) {
        rememberMessageListScope().Render(
            state = canonical,
            dispatchEvent = dispatchEvent,
            modifier = modifier,
        )
    }
}

/** Keep cache writes in LiveMailViewModel, which owns the pending-action journal and reload. */
internal fun handleCanonicalMessageListEvent(
    event: MessageListEvent,
    mailbox: LiveMailState,
    dispatch: (LiveMailEvent) -> Unit,
) {
    if (mailbox.busy) return
    when (event) {
        is MessageItemEvent -> handleMessageItemEvent(event, mailbox, dispatch)
        MessageListEvent.ExitSelectionMode -> dispatch(LiveMailEvent.Selection(MailSelectionAction.Clear))
        MessageListEvent.EnterSelectionMode -> dispatch(LiveMailEvent.Selection(MailSelectionAction.Start))
        MessageListEvent.Refresh -> dispatch(LiveMailEvent.Refresh)
        else -> Unit
    }
}

private fun handleMessageItemEvent(
    event: MessageItemEvent,
    mailbox: LiveMailState,
    dispatch: (LiveMailEvent) -> Unit,
) {
    when (event) {
        is MessageItemEvent.OnMessageClick -> {
            val key = mailbox.keyFor(event.message) ?: return
            if (mailbox.selection.active) {
                dispatch(LiveMailEvent.Selection(MailSelectionAction.Toggle(key)))
            } else {
                dispatch(LiveMailEvent.OpenMessage(key.uid))
            }
        }

        is MessageItemEvent.ToggleSelectMessages -> mailbox.toggleSelection(event.messages, dispatch)

        is MessageItemEvent.ToggleFavourite -> {
            event.messages.singleOrNull()?.let { message ->
                mailbox.queueSingleFlag(message, Flag.FLAGGED, !message.starred, dispatch)
            }
        }

        is MessageItemEvent.ToggleReadUnread -> {
            event.messages.singleOrNull()?.let { message ->
                mailbox.queueSingleFlag(message, Flag.SEEN, message.state != MessageItemUi.State.Read, dispatch)
            }
        }

        MessageItemEvent.SelectAll -> {
            if (!mailbox.selection.active) dispatch(LiveMailEvent.Selection(MailSelectionAction.Start))
            dispatch(LiveMailEvent.Selection(MailSelectionAction.All))
        }

        MessageItemEvent.DeselectAll -> dispatch(LiveMailEvent.Selection(MailSelectionAction.Clear))

        else -> Unit
    }
}

private fun LiveMailState.toggleSelection(messages: List<MessageItemUi>, dispatch: (LiveMailEvent) -> Unit) {
    val keys = messages.mapNotNull(::keyFor)
    if (keys.isNotEmpty()) {
        if (!selection.active) dispatch(LiveMailEvent.Selection(MailSelectionAction.Start))
        keys.forEach { key -> dispatch(LiveMailEvent.Selection(MailSelectionAction.Toggle(key))) }
    }
}

private fun LiveMailState.keyFor(message: MessageItemUi): MailMessageKey? {
    val key = account?.let { owner -> folder?.let { MailMessageKey(owner, it.id, message.messageReference) } }
        ?: return null
    return key.takeIf { message.account.id == it.account && messages.any { row -> row.id == it.uid } }
}

private fun LiveMailState.queueSingleFlag(
    message: MessageItemUi,
    flag: Flag,
    enabled: Boolean,
    dispatch: (LiveMailEvent) -> Unit,
) {
    if (selection.active) return
    val key = keyFor(message) ?: return
    dispatch(LiveMailEvent.Selection(MailSelectionAction.Start))
    dispatch(LiveMailEvent.Selection(MailSelectionAction.Toggle(key)))
    dispatch(LiveMailEvent.Selection(MailSelectionAction.ApplyFlag(flag, enabled)))
}
