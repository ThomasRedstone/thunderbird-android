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
        val record = state.pending?.record
        if (record != null) {
            TextBodyLarge(
                if (record.phase == ExportLeasePhase.READY) {
                    "Choose where to save the complete cached message (.eml)."
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
