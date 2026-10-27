package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.Composable
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

@Composable
internal fun MailReaderAttachments(content: MailContent) {
    if (!content.complete) {
        TextBodyLarge("Download the complete message to list attachments.")
    } else if (content.attachments.isNotEmpty()) {
        TextBodyLarge("Attachments (${content.attachments.size})")
        content.attachments.forEach {
            TextBodyLarge(it.displayName)
            TextBodyLarge(it.mimeType)
        }
    }
}
