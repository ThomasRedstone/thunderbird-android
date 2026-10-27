package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge

@Composable
internal fun LiveMailScreen(state: LiveMailState, onEvent: (LiveMailEvent) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextTitleLarge("Mailboxes")
        TextBodyLarge("Foreground preview · Cached mail opens offline")
        if (state.account != null) {
            ButtonText("Back", { onEvent(LiveMailEvent.Back) }, enabled = !state.busy)
            ButtonText(
                if (state.folder == null) "Refresh folders" else "Sync folder",
                { onEvent(LiveMailEvent.Refresh) },
                enabled = !state.busy,
            )
        }
        MailOperationStatus(state.busy, state.canCancel, state.cancelling, "Cancel operation") {
            onEvent(LiveMailEvent.Cancel)
        }
        state.notice?.let { TextBodyLarge(it) }
        val content = state.content
        when {
            content != null -> {
                if (!content.complete) {
                    TextBodyLarge("Only a preview is cached.")
                    ButtonText("Download full message", { onEvent(LiveMailEvent.Download) }, enabled = !state.busy)
                }
                MailReader(content, Modifier.weight(1f))
            }

            state.folder != null -> {
                MailMessageList(state, onEvent, Modifier.weight(1f))
            }

            state.account != null -> {
                if (state.folders.isEmpty() &&
                    !state.busy
                ) {
                    TextBodyLarge("No cached folders. Refresh to discover folders.")
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.folders, key = { it.id }) { folder ->
                        ButtonText(
                            folder.name,
                            { onEvent(LiveMailEvent.OpenFolder(folder)) },
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            enabled = !state.busy,
                        )
                    }
                }
            }

            else -> {
                if (state.accounts.isEmpty()) TextBodyLarge("Add an account in Accounts to get started.")
                LazyColumn(Modifier.weight(1f)) {
                    items(state.accounts, key = { it.id.toString() }) { account ->
                        ButtonText(
                            account.displayName,
                            { onEvent(LiveMailEvent.OpenAccount(account.id)) },
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MailReader(content: MailContent, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(12.dp)) {
            TextTitleLarge(content.summary.subject)
            TextBodyLarge(content.summary.sender)
            TextBodyLarge(content.body)
        }
    }
}

@Composable
private fun MailMessageList(
    state: LiveMailState,
    onEvent: (LiveMailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TextTitleLarge(checkNotNull(state.folder).name)
        if (state.messages.isEmpty() && !state.busy) TextBodyLarge("No cached messages. Sync to receive mail.")
        LazyColumn(Modifier.weight(1f)) {
            items(state.messages, key = { it.id }) { message ->
                ButtonText(
                    "${if (message.unread) "Unread · " else ""}${message.subject}\n${message.sender}",
                    { onEvent(LiveMailEvent.OpenMessage(message.id)) },
                    Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    enabled = !state.busy,
                )
            }
        }
    }
}
