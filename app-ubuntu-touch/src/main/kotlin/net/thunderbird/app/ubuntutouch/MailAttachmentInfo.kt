package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import java.util.Locale

internal data class MailAttachmentInfo(val displayName: String, val mimeType: String) {
    override fun toString(): String = "MailAttachmentInfo(redacted)"
}

internal fun attachmentParts(message: Message): List<Part> = mutableListOf<Part>().also {
    MessageExtractor.findViewablesAndAttachments(message, null, it)
}

internal fun attachmentInfo(part: Part): MailAttachmentInfo {
    val name = MimeUtility.getHeaderParameter(part.disposition, "filename")
        ?: MimeUtility.getHeaderParameter(part.contentType, "name")
    val mime = part.mimeType?.lowercase(Locale.ROOT)?.takeIf { MIME_TYPE.matches(it) }
        ?: "application/octet-stream"
    return MailAttachmentInfo(safeAttachmentName(name), mime)
}

private val MIME_TYPE = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
