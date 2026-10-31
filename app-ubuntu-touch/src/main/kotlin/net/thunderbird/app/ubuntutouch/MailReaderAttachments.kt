package net.thunderbird.app.ubuntutouch

import net.thunderbird.feature.mail.message.reader.api.ui.MessageReaderAttachment

internal fun MailContent.readerAttachments(): List<MessageReaderAttachment> =
    attachments.map { MessageReaderAttachment(it.displayName, it.mimeType) }
