package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.Viewable
import org.jsoup.Jsoup

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

/** Read cached HTML as inert text. Parsing this string does not load images, styles or links. */
internal fun htmlTextBody(message: Message): String? {
    val viewables = mutableListOf<Viewable>()
    MessageExtractor.findViewablesAndAttachments(message, viewables, null)
    val parts = htmlParts(viewables)
    val text = parts.map(::htmlPartText)
    return if (parts.isEmpty() || text.any { it == null }) {
        null
    } else {
        text.filterNotNull().joinToString("\n").take(MAX_DISPLAY_CHARS).ifBlank { null }
    }
}

private fun htmlPartText(part: Part): String? {
    val html = MessageExtractor.getTextFromPart(part)?.takeIf { it.length <= MAX_HTML_CHARS } ?: return null
    val body = Jsoup.parse(html).body()
    body.select("script, style, svg, iframe, object, form, video, audio").remove()
    return body.text()
}

private fun htmlParts(viewables: List<Viewable>): List<Part> = viewables.flatMap {
    when (it) {
        is Viewable.Html -> listOf(it.part)
        is Viewable.Alternative -> htmlParts(it.html)
        else -> emptyList()
    }
}

private const val MAX_HTML_CHARS = 1_000_000
private const val MAX_DISPLAY_CHARS = 200_000
