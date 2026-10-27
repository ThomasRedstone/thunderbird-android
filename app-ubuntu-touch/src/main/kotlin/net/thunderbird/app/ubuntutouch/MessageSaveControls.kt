package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

@Composable
internal fun MessageSaveControls(state: MessageSaveState, onEvent: (MessageSaveEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.notice?.let { TextBodyLarge(it) }
        if (state.busy) TextBodyLarge("Preparing or checking file transfer…")
        SaveTransferOutcome(state)
        AttachmentChoices(state, onEvent)
        val record = state.pending?.record
        if (record != null) {
            TextBodyLarge(
                if (record.phase == ExportLeasePhase.READY) {
                    "Choose where to save this file."
                } else {
                    "File transfer pending. Its private copy is retained until completion is confirmed."
                },
            )
            if (state.pending.state == ExportBrokerState.COLLECTED) {
                TextBodyLarge("The receiver collected the file; final completion is still pending.")
            }
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                state.destinations.forEach { destination ->
                    ButtonText(destination.name, {
                        onEvent(MessageSaveEvent.Select(destination))
                    }, enabled = !state.busy)
                }
            }
            ButtonText("Check transfer / reload destinations", {
                onEvent(MessageSaveEvent.Refresh)
            }, enabled = !state.busy)
            ButtonText("Cancel transfer", { onEvent(MessageSaveEvent.Cancel) }, enabled = !state.busy)
            if (record.phase.hasTransfer) {
                if (state.confirmDiscard) {
                    TextBodyLarge(
                        "Delete only this app's temporary export copy? This does not recall any receiver copy, " +
                            "confirm cancellation, or delete the cached message.",
                    )
                    ButtonText("Delete temporary copy", {
                        onEvent(MessageSaveEvent.ConfirmDiscard)
                    }, enabled = !state.busy)
                    ButtonText("Keep temporary copy", { onEvent(MessageSaveEvent.Keep) }, enabled = !state.busy)
                } else {
                    ButtonText("Review temporary copy cleanup", {
                        onEvent(MessageSaveEvent.ReviewDiscard)
                    }, enabled = !state.busy)
                }
            }
        } else if (state.notice != null) {
            ButtonText("Check pending transfers", { onEvent(MessageSaveEvent.Refresh) }, enabled = !state.busy)
        }
    }
}

@Composable
internal fun ReaderSaveAction(state: LiveMailState, onSave: ((MessageSaveEvent) -> Unit)?) {
    val request = state.saveRequest()
    if (onSave != null && request != null) {
        ButtonText("Save message (.eml)", { onSave(request) }, enabled = !state.busy)
    }
}

internal fun LiveMailState.saveRequest(): MessageSaveEvent.Prepare? {
    val message = content
    return if (message?.complete == true && account != null && folder != null) {
        MessageSaveEvent.Prepare(account, folder.id, message.summary.id)
    } else {
        null
    }
}

@Composable
private fun SaveTransferOutcome(state: MessageSaveState) {
    if (state.pending?.record != null || state.busy || state.attachments.isNotEmpty()) return
    val message = when (state.pending?.state) {
        ExportBrokerState.FINALIZED -> "The transfer service confirmed completion."
        ExportBrokerState.ABORTED -> "The transfer service confirmed cancellation."
        else -> if (state.notice == null) "No pending message transfer." else null
    }
    message?.let { TextBodyLarge(it) }
}

@Composable
private fun AttachmentChoices(state: MessageSaveState, onEvent: (MessageSaveEvent) -> Unit) {
    if (state.attachments.isEmpty()) return
    TextBodyLarge("Choose an attachment to save")
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        state.attachments.forEach { attachment ->
            ButtonText(attachment.displayName, {
                val reference = attachment.reference
                onEvent(MessageSaveEvent.Prepare(reference.account, reference.folder, reference.uid, reference))
            }, enabled = !state.busy)
            TextBodyLarge(attachment.mimeType)
        }
    }
}

@Composable
internal fun ReaderAttachmentAction(state: LiveMailState, onSave: ((MessageSaveEvent) -> Unit)?) {
    val request = state.saveRequest()
    if (onSave != null && request != null && state.content?.attachments?.isNotEmpty() == true) {
        ButtonText("Save attachments", {
            onSave(MessageSaveEvent.Attachments(MailMessageKey(request.account, request.folder, request.uid)))
        }, enabled = !state.busy)
    }
}
