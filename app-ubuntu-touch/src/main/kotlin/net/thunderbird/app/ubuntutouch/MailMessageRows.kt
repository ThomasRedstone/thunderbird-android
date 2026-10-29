package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState

@Composable
internal fun MailMessageRows(
    state: LiveMailState,
    onEvent: (LiveMailEvent) -> Unit,
    messageListRenderer: MessageListContract.MessageListScreenRenderer?,
    canonicalState: MessageListState?,
    onCanonicalEvent: ((MessageListEvent) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val canonical = if (state.query.isBlank()) state.canonicalMessageList() else null
    if (canonical != null && messageListRenderer != null) {
        val displayState = canonicalState?.takeIf { it.metadata.folder == canonical.metadata.folder }
        if (displayState == null || onCanonicalEvent == null) {
            TextBodyLarge("Loading cached messages…", modifier)
        } else {
            CanonicalMessageListScreen(
                displayState,
                onCanonicalEvent,
                messageListRenderer,
                modifier,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.testTag("cached-message-list"),
            state = rememberMailSortViewport(state.sort, state.busy),
        ) {
            items(state.searchHits, key = { listOf(it.account.toString(), it.folder.id, it.message.id) }) { hit ->
                SearchResultButton(state, hit, onEvent)
            }
            items(state.messages, key = { it.id }) { message ->
                MailFolderMessageButton(state, message, onEvent)
            }
        }
    }
}
