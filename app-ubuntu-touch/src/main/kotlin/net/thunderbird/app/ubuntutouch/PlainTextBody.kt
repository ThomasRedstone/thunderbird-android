package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.Viewable

/** Reuse the engine's attachment classification and alternative selection, retaining body order. */
internal fun plainTextBody(message: Message): String? {
    val viewables = mutableListOf<Viewable>()
    MessageExtractor.findViewablesAndAttachments(message, viewables, null)
    val parts = plainTextParts(viewables)
    return if (parts.isEmpty()) {
        null
    } else {
        parts.map {
            MessageExtractor.getTextFromPart(it) ?: return null
        }.joinToString("\n")
    }
}

private fun plainTextParts(viewables: List<Viewable>): List<Part> = viewables.flatMap {
    when (it) {
        is Viewable.Text -> listOf(it.part)
        is Viewable.Alternative -> plainTextParts(it.text)
        else -> emptyList()
    }
}
