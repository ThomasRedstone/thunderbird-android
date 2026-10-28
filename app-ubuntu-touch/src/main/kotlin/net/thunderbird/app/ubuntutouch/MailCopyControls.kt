package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.Composable
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

internal fun MailMoveUiState.needsCopyReview(): Boolean {
    val source = overview.source
    val destination = overview.destinations.find { it.folder.id == target }
    return if (kind == MailMoveKind.COPY && source?.generation != null && destination?.generation != null) {
        overview.history.any { it.matchesCompletedCopy(source, destination) }
    } else {
        false
    }
}

internal fun MailMoveUiState.forCopyReview(event: MailMoveEvent): MailMoveUiState? = when (event) {
    MailMoveEvent.ReviewCopyAgain -> if (needsCopyReview()) copy(confirmCopyAgain = true) else this
    MailMoveEvent.KeepCopies -> copy(confirmCopyAgain = false)
    else -> null
}

internal fun MailMoveUiState.operationTitle(): String = when {
    selected != null -> "${selected.kind.title} message"
    role != null -> "${role.title} message"
    request != null -> "${kind.title} message"
    else -> "Message operations"
}

@Composable
internal fun FilingQueueControls(state: MailMoveUiState, onEvent: (MailMoveEvent) -> Unit) {
    val ready = !state.busy && state.overview.source?.verified == true && state.roleMatchesTarget() &&
        state.overview.destinations.any { it.folder.id == state.target && it.ready }
    CopySourceNotice(state.kind)
    if (state.needsCopyReview()) {
        if (state.confirmCopyAgain) {
            TextBodyLarge("A completed copy is already recorded here. Queue another copy only if you want duplicates.")
            ButtonText("Queue another copy", { onEvent(MailMoveEvent.ConfirmCopyAgain) }, enabled = ready)
            ButtonText("Keep existing copies", { onEvent(MailMoveEvent.KeepCopies) }, enabled = !state.busy)
        } else {
            ButtonText("Review another copy", { onEvent(MailMoveEvent.ReviewCopyAgain) }, enabled = ready)
        }
    } else {
        ButtonText("Queue ${state.kind.title.lowercase()}", { onEvent(MailMoveEvent.Queue) }, enabled = ready)
    }
    TextBodyLarge("Queueing saves intent locally. ${state.kind.title} now contacts the server from the review screen.")
}

@Composable
internal fun ReaderCopyAction(state: LiveMailState, onMove: ((MailMoveEvent.Open) -> Unit)?) {
    val key = state.moveRequest() ?: return
    if (onMove == null) return
    ButtonText("Copy message", { onMove(MailMoveEvent.Open(key, kind = MailMoveKind.COPY)) }, enabled = !state.busy)
}

private fun MoveView.matchesCompletedCopy(source: MoveSource, destination: MoveDestination): Boolean =
    sameConnection && move.kind == MailMoveKind.COPY && move.state == MailMoveState.COMPLETE &&
        move.account == source.key.account && move.source == source.key.folder && move.uid == source.key.uid &&
        move.sourceValidity == source.generation && move.target == destination.folder.id &&
        move.targetValidity == destination.generation

@Composable
internal fun CopySourceNotice(kind: MailMoveKind) {
    if (kind == MailMoveKind.COPY) TextBodyLarge("Copy keeps the source message.")
}
