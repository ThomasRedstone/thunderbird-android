package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge

@Composable
internal fun MailMoveScreen(state: MailMoveUiState, onEvent: (MailMoveEvent) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextTitleLarge(state.operationTitle())
        MailOperationStatus(state.busy, state.canCancel, state.cancelling, "Cancel message operation") {
            onEvent(MailMoveEvent.Cancel)
        }
        state.notice?.let { TextBodyLarge(it) }
        val page = state.page
        val selected = state.selected
        when {
            page != null -> {
                ButtonText("Back to review", { onEvent(MailMoveEvent.Back) }, enabled = !state.busy)
                TextBodyLarge("Retained local copy · Opening these bytes works offline")
                MailSourceScreen(page, state.busy, {
                    if (it is LiveMailEvent.Source) onEvent(MailMoveEvent.Source(it.offset))
                }, Modifier.weight(1f))
            }

            selected != null -> {
                val view = state.overview.history.find {
                    it.move.id == selected.id &&
                        it.move.account == selected.account
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ButtonText("Back to operations", { onEvent(MailMoveEvent.Back) }, enabled = !state.busy)
                    if (view != null) MoveDetails(state, view, onEvent)
                }
            }

            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { MoveSetup(state, onEvent) }
                item { TextTitleLarge("Saved operations") }
                items(state.overview.history, key = { it.move.id }) { view ->
                    ButtonText(
                        "${view.move.kind.title}: ${view.accountName}: ${view.move.source} → ${view.move.target} · " +
                            view.move.state.displayName(),
                        {
                            onEvent(MailMoveEvent.Select(view.move))
                        },
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoveSetup(state: MailMoveUiState, onEvent: (MailMoveEvent) -> Unit) {
    val source = state.overview.source
    if (source == null) {
        TextBodyLarge("Open a cached message to choose a destination, or review a saved move below.")
        return
    }
    TextBodyLarge(source.subject.ifBlank { "(No subject)" })
    TextBodyLarge("From folder: ${source.key.folder}")
    FolderRoleControls(state, onEvent)
    if (!source.verified) {
        TextBodyLarge("This cached message needs verification with the current account connection.")
        ButtonText("Verify source message", { onEvent(MailMoveEvent.Verify) }, enabled = !state.busy)
    }
    state.overview.destinations.forEach { target ->
        ButtonText("${if (state.target == target.folder.id) "Selected: " else ""}${target.folder.name}", {
            onEvent(MailMoveEvent.Target(target.folder.id))
        }, enabled = !state.busy)
    }
    val target = state.overview.destinations.find { it.folder.id == state.target }
    if (target != null && !target.ready) {
        ButtonText("Sync selected destination", { onEvent(MailMoveEvent.SyncDestination) }, enabled = !state.busy)
    }
    FilingQueueControls(state, onEvent)
}

@Composable
private fun MoveDetails(state: MailMoveUiState, view: MoveView, onEvent: (MailMoveEvent) -> Unit) {
    TextBodyLarge("${view.move.kind.title}: ${view.accountName}: ${view.move.source} → ${view.move.target}")
    CopySourceNotice(view.move.kind)
    TextBodyLarge(
        when (view.move.state) {
            MailMoveState.QUEUED -> "Queued locally; not submitted."
            MailMoveState.WORKING -> "An attempt is in progress. Reopen the app to review an interrupted result."
            MailMoveState.UNKNOWN -> "Server outcome unknown. Inspect source and destination before retrying."
            MailMoveState.COMPLETE -> "Server acknowledged the command. Sync folders to verify the destination cache."
        },
    )
    if (!view.sameConnection) TextBodyLarge("Account connection changed. The retained copy can still be read.")
    val payload = view.retained
    if (payload != null) {
        TextBodyLarge(
            "Retained ${payload.bytes} bytes · " +
                if (payload.complete) "Complete cached message" else "Cached preview only",
        )
        ButtonText("Read retained source", { onEvent(MailMoveEvent.Source()) }, enabled = !state.busy)
    } else {
        TextBodyLarge("No retained cached copy is available.")
    }
    when (view.move.state) {
        MailMoveState.QUEUED -> {
            ButtonText(
                "${view.move.kind.title} now",
                { onEvent(MailMoveEvent.Submit) },
                enabled =
                !state.busy && view.sameConnection,
            )
            ButtonText("Cancel queued operation", { onEvent(MailMoveEvent.CancelQueued) }, enabled = !state.busy)
        }

        MailMoveState.UNKNOWN -> MoveRetryControls(state, view, onEvent)

        else -> Unit
    }
    if (view.move.state in setOf(MailMoveState.UNKNOWN, MailMoveState.COMPLETE)) {
        ButtonText(
            "Sync source and destination",
            { onEvent(MailMoveEvent.Reconcile) },
            enabled =
            !state.busy && view.sameConnection,
        )
        TextBodyLarge("Sync never repeats an operation. Unverified retained copies are kept.")
    }
    if (view.move.uncertain) {
        TextBodyLarge(
            "An earlier attempt was uncertain; check the destination for duplicate copies.",
        )
    }
}

@Composable
private fun MoveRetryControls(state: MailMoveUiState, view: MoveView, onEvent: (MailMoveEvent) -> Unit) {
    if (state.confirmRetry) {
        TextBodyLarge(
            "Retrying may create a second copy or repeat only part of the operation. " +
                "Continue only after checking both folders.",
        )
        ButtonText(
            "Retry ${view.move.kind.title.lowercase()} despite risk",
            { onEvent(MailMoveEvent.ConfirmRetry) },
            enabled =
            !state.busy && view.sameConnection,
        )
        ButtonText("Keep for review", { onEvent(MailMoveEvent.Keep) }, enabled = !state.busy)
    } else {
        ButtonText(
            "Review retry risk",
            { onEvent(MailMoveEvent.ReviewRetry) },
            enabled =
            !state.busy && view.sameConnection,
        )
    }
}

@Composable
internal fun ReaderMoveAction(state: LiveMailState, onMove: ((MailMoveEvent.Open) -> Unit)?) {
    val request = state.moveRequest()
    if (onMove != null && request != null) {
        ButtonText("Move message", { onMove(MailMoveEvent.Open(request)) }, enabled = !state.busy)
    }
}

internal fun LiveMailState.moveRequest(): MailMessageKey? {
    val message = content ?: return null
    val owner = account
    val source = folder
    return if (owner != null && source != null) MailMessageKey(owner, source.id, message.summary.id) else null
}

@Composable
internal fun ReaderTools(
    state: LiveMailState,
    onSave: ((MessageSaveEvent) -> Unit)?,
    onMove: ((MailMoveEvent.Open) -> Unit)?,
    onEvent: (LiveMailEvent) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ButtonText("View source", { onEvent(LiveMailEvent.Source()) }, enabled = !state.busy)
        ReaderSaveAction(state, onSave)
        ReaderAttachmentAction(state, onSave)
        ReaderMoveAction(state, onMove)
        ReaderCopyAction(state, onMove)
        ReaderFolderRoleActions(state, onMove)
    }
}

private fun MailMoveState.displayName(): String = when (this) {
    MailMoveState.QUEUED -> "Queued"
    MailMoveState.WORKING -> "Submitting"
    MailMoveState.UNKNOWN -> "Needs review"
    MailMoveState.COMPLETE -> "Server acknowledged"
}
