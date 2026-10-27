package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.core.common.mail.Flag

@Composable
internal fun MailSelectionControls(state: LiveMailState, onEvent: (LiveMailEvent) -> Unit) {
    Column {
        fun select(action: MailSelectionAction) = onEvent(LiveMailEvent.Selection(action))
        if (!state.selection.active) {
            ButtonText("Select messages", { select(MailSelectionAction.Start) }, enabled = !state.busy)
        } else {
            TextBodyLarge("${state.selection.keys.size} selected")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ButtonText("Cancel selection", { select(MailSelectionAction.Clear) }, enabled = !state.busy)
                ButtonText("Select listed (up to $MAX_MAIL_SELECTION)", {
                    select(MailSelectionAction.All)
                }, enabled = !state.busy)
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(Flag.SEEN to "Mark read", Flag.FLAGGED to "Star").forEach { (flag, label) ->
                    ButtonText(
                        label,
                        { select(MailSelectionAction.ApplyFlag(flag, true)) },
                        enabled = !state.busy && state.selection.keys.isNotEmpty(),
                    )
                    ButtonText(if (flag == Flag.SEEN) "Mark unread" else "Remove star", {
                        select(MailSelectionAction.ApplyFlag(flag, false))
                    }, enabled = !state.busy && state.selection.keys.isNotEmpty())
                }
            }
        }
    }
}

internal fun selectionLabel(state: LiveMailState, key: MailMessageKey): String =
    if (!state.selection.active) {
        ""
    } else if (key in state.selection.keys) {
        "Selected · "
    } else {
        "Select · "
    }

@Composable
internal fun MailFolderMessageButton(state: LiveMailState, message: MailSummary, onEvent: (LiveMailEvent) -> Unit) {
    val key = MailMessageKey(checkNotNull(state.account), checkNotNull(state.folder).id, message.id)
    ButtonText(
        selectionLabel(state, key) + messageLabel(message),
        {
            onEvent(
                if (state.selection.active) {
                    LiveMailEvent.Selection(MailSelectionAction.Toggle(key))
                } else {
                    LiveMailEvent.OpenMessage(message.id)
                },
            )
        },
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = !state.busy,
    )
}
