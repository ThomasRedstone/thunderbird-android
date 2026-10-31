package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlined
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState
import net.thunderbird.feature.mail.message.reader.api.ui.MessageReaderPage
import net.thunderbird.feature.mail.message.reader.api.ui.MessageReaderPageAction
import net.thunderbird.feature.mail.message.reader.api.ui.MessageReaderPageContent
import net.thunderbird.feature.mail.message.reader.api.ui.adjacentMessage

@Composable
@Suppress("LongMethod") // Routes the mailbox, folder, reader and source views within one screen.
internal fun LiveMailScreen(
    state: LiveMailState,
    keyboard: MaliitKeyboard? = null,
    openNavigation: (() -> Unit)? = null,
    onSave: ((MessageSaveEvent) -> Unit)? = null,
    onMove: ((MailMoveEvent.Open) -> Unit)? = null,
    messageListRenderer: MessageListContract.MessageListScreenRenderer? = null,
    canonicalState: MessageListState? = null,
    onCanonicalEvent: ((MessageListEvent) -> Unit)? = null,
    onEvent: (LiveMailEvent) -> Unit,
) {
    val height = keyboard?.height?.collectAsState()?.value ?: 0
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(0)
    val inset = with(LocalDensity.current) { height.coerceIn(0, windowHeight).toDp() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).padding(bottom = inset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            if (openNavigation != null) ButtonText("Menu", openNavigation, enabled = !state.busy)
            TextTitleLarge("Mailboxes")
        }
        ReaderNavigationControls(state, onEvent)
        val content = state.content
        when {
            state.source != null -> MailSourceScreen(state.source, state.busy, onEvent, Modifier.weight(1f))

            content != null -> CachedReader(state, content, onSave, onMove, onEvent, Modifier.weight(1f))

            state.folder != null -> {
                MailMessageList(
                    state,
                    onEvent,
                    keyboard,
                    messageListRenderer,
                    canonicalState,
                    onCanonicalEvent,
                    Modifier.weight(1f),
                )
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
private fun CachedReader(
    state: LiveMailState,
    content: MailContent,
    onSave: ((MessageSaveEvent) -> Unit)?,
    onMove: ((MailMoveEvent.Open) -> Unit)?,
    onEvent: (LiveMailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = if (state.searchAllFolders) emptyList() else state.messages.map { it.id }
    val previous = adjacentMessage(ids, content.summary.id, -1)
    val next = adjacentMessage(ids, content.summary.id, 1)
    Column(modifier) {
        ReaderTools(state, onSave, onMove, onEvent)
        MessageReaderPage(
            MessageReaderPageContent(
                subject = content.summary.subject,
                sender = content.summary.sender,
                body = content.body,
                accountName = state.accounts.find { it.id == state.account }?.displayName,
                complete = content.complete,
                plainText = content.plainText,
                unread = content.summary.unread,
                starred = content.summary.starred,
                previousAvailable = previous != null,
                nextAvailable = next != null,
                busy = state.busy,
            ),
            Modifier.weight(1f),
            onAction = { action -> onReaderAction(action, content, previous, next, onEvent) },
        ) { MailReaderAttachments(content) }
    }
}

private fun onReaderAction(
    action: MessageReaderPageAction,
    content: MailContent,
    previous: String?,
    next: String?,
    onEvent: (LiveMailEvent) -> Unit,
) {
    when (action) {
        MessageReaderPageAction.DOWNLOAD -> onEvent(LiveMailEvent.Download)
        MessageReaderPageAction.REPLY -> onEvent(LiveMailEvent.Respond(ResponseMode.REPLY))
        MessageReaderPageAction.REPLY_ALL -> onEvent(LiveMailEvent.Respond(ResponseMode.REPLY_ALL))
        MessageReaderPageAction.FORWARD -> onEvent(LiveMailEvent.Respond(ResponseMode.FORWARD))
        MessageReaderPageAction.TOGGLE_READ -> onEvent(LiveMailEvent.ChangeFlag(Flag.SEEN, content.summary.unread))
        MessageReaderPageAction.TOGGLE_STAR -> onEvent(LiveMailEvent.ChangeFlag(Flag.FLAGGED, !content.summary.starred))
        MessageReaderPageAction.PREVIOUS -> previous?.let { onEvent(LiveMailEvent.OpenMessage(it)) }
        MessageReaderPageAction.NEXT -> next?.let { onEvent(LiveMailEvent.OpenMessage(it)) }
    }
}

@Composable
internal fun MailboxControls(state: LiveMailState, onEvent: (LiveMailEvent) -> Unit) {
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
    if (state.pendingActions >
        0
    ) {
        TextBodyLarge("${state.pendingActions} local changes · Sync each changed folder to apply")
    }
    state.notice?.let { TextBodyLarge(it) }
    if (state.pendingActions > 0) {
        ButtonText("Sync queued changes", { onEvent(LiveMailEvent.SyncQueuedFlags) }, enabled = !state.busy)
        if (state.searchScope == CachedSearchScope.ALL_ACCOUNTS) {
            TextBodyLarge("Open an account to review or discard its local changes.")
        } else if (state.confirmDiscard) {
            TextBodyLarge("Discard local changes and use the last cached server flags?")
            ButtonText("Discard changes", { onEvent(LiveMailEvent.ConfirmDiscard) }, enabled = !state.busy)
            ButtonText("Keep changes", { onEvent(LiveMailEvent.CancelDiscard) }, enabled = !state.busy)
        } else {
            ButtonText("Review local changes", { onEvent(LiveMailEvent.DiscardPending) }, enabled = !state.busy)
        }
    }
}

@Composable
private fun MailMessageList(
    state: LiveMailState,
    onEvent: (LiveMailEvent) -> Unit,
    keyboard: MaliitKeyboard?,
    messageListRenderer: MessageListContract.MessageListScreenRenderer?,
    canonicalState: MessageListState?,
    onCanonicalEvent: ((MessageListEvent) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editing by rememberMaliitEditor("${state.account}/${state.folder?.id}", state.query, keyboard) {}
    Column(modifier) {
        TextTitleLarge(
            when (state.searchScope) {
                CachedSearchScope.FOLDER -> checkNotNull(state.folder).name
                CachedSearchScope.ACCOUNT -> "All cached folders"
                CachedSearchScope.ALL_ACCOUNTS -> "All cached accounts"
            },
        )
        SearchAndSortControls(state, onEvent)
        MailSelectionControls(state, onEvent)
        TextFieldOutlined(
            value = editing,
            onValueChange = { editing = it.copy(text = it.text.take(MAX_SEARCH_LENGTH)) },
            label = "Search cached sender, subject and text",
            modifier = Modifier.fillMaxWidth().onFocusChanged { keyboard?.focus(it.isFocused) },
            isEnabled = !state.busy,
        )
        Row {
            ButtonText("Search", {
                keyboard?.focus(false)
                onEvent(LiveMailEvent.Search(editing.text))
            }, enabled = !state.busy)
            ButtonText("Clear search", {
                editing = TextFieldValue("")
                keyboard?.focus(false)
                onEvent(LiveMailEvent.Search(""))
            }, enabled = !state.busy)
        }
        val count = if (state.searchAllFolders) state.searchHits.size else state.messages.size
        if (state.query.isNotBlank()) {
            TextBodyLarge(
                "$count cached matches · ${state.searchScope.label()}",
            )
        }
        TextBodyLarge("Offline search · Uncached text, HTML and attachments are not searched")
        if (count == 0 && !state.busy) {
            TextBodyLarge(
                if (state.query.isBlank()) "No cached messages. Sync to receive mail." else "No cached matches",
            )
        }
        MailMessageRows(state, onEvent, messageListRenderer, canonicalState, onCanonicalEvent, Modifier.weight(1f))
    }
}

internal fun messageLabel(message: MailSummary): String =
    "${if (message.starred) "Starred · " else ""}${if (message.unread) "Unread · " else ""}" +
        "${message.subject}\n${message.sender}"

@Composable
internal fun SearchResultButton(state: LiveMailState, hit: CachedMailHit, onEvent: (LiveMailEvent) -> Unit) {
    ButtonText(
        selectionLabel(state, MailMessageKey(hit.account, hit.folder.id, hit.message.id)) +
            "${if (state.searchScope == CachedSearchScope.ALL_ACCOUNTS) accountLabel(state, hit) else ""}" +
            "${hit.folder.name}\n${messageLabel(hit.message)}",
        {
            onEvent(
                if (state.selection.active) {
                    LiveMailEvent.Selection(
                        MailSelectionAction.Toggle(MailMessageKey(hit.account, hit.folder.id, hit.message.id)),
                    )
                } else {
                    LiveMailEvent.OpenSearchResult(hit)
                },
            )
        },
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = !state.busy,
    )
}

@Composable
private fun SearchAndSortControls(state: LiveMailState, onEvent: (LiveMailEvent) -> Unit) {
    val busy = state.busy
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ButtonText("This folder", {
            onEvent(LiveMailEvent.SearchScope(CachedSearchScope.FOLDER))
        }, enabled = !busy)
        ButtonText("This account", {
            onEvent(LiveMailEvent.SearchScope(CachedSearchScope.ACCOUNT))
        }, enabled = !busy)
        ButtonText("All accounts", {
            onEvent(LiveMailEvent.SearchScope(CachedSearchScope.ALL_ACCOUNTS))
        }, enabled = !busy)
    }
    ButtonText("Sort: ${state.sort.label}", {
        val next = MailSort.entries[(state.sort.ordinal + 1) % MailSort.entries.size]
        onEvent(LiveMailEvent.Sort(next))
    }, enabled = !busy)
}

private fun CachedSearchScope.label(): String = when (this) {
    CachedSearchScope.FOLDER -> "This folder only"
    CachedSearchScope.ACCOUNT -> "This account"
    CachedSearchScope.ALL_ACCOUNTS -> "All accounts"
}

private fun accountLabel(state: LiveMailState, hit: CachedMailHit): String =
    "${state.accounts.find { it.id == hit.account }?.displayName ?: "Unavailable account"}\n"
