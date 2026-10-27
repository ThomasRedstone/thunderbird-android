package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.Composable
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

@Composable
internal fun MailOperationStatus(
    busy: Boolean,
    canCancel: Boolean,
    cancelling: Boolean,
    cancelLabel: String,
    onCancel: () -> Unit,
) {
    if (busy) TextBodyLarge(if (cancelling) "Cancelling…" else "Working…")
    if (canCancel) ButtonText(cancelLabel, onCancel, enabled = !cancelling)
}
