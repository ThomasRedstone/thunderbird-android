package net.thunderbird.feature.mail.message.reader.impl.ui

import com.fsck.k9.mail.internet.MimeBodyPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import net.thunderbird.feature.mail.message.reader.api.domain.mapper.AttachmentViewInfoMapper
import net.thunderbird.feature.mail.message.reader.api.ui.MessageReaderViewContract
import net.thunderbird.feature.mail.message.reader.api.ui.attachment.AttachmentUiItem
import net.thunderbird.feature.mail.message.reader.api.ui.attachment.AttachmentUri

class JvmMailPartTest {
    @Test
    fun `shared reader retains an engine MIME part on JVM`() {
        val part: MailPart = MimeBodyPart()
        val attachment = object : AttachmentViewInfoMapper.AttachmentMetadata<MailPart> {
            override val uri = "cache:fixture"
            override val filename = "fixture.pdf"
            override val isSupportedImage = false
            override val isContentAvailable = true
            override fun getSize() = 12L
            override fun isInlineAttachment() = false
            override fun getMimeType() = "application/pdf"
            override fun getPart() = part
        }
        val testSubject = MessageReaderViewModel(JvmAttachmentMapper())

        testSubject.event(
            MessageReaderViewContract.Event.UpdateAttachments(
                nonInlineAttachments = listOf(attachment),
                extraNonInlineAttachments = emptyList(),
            ),
        )

        val displayed = testSubject.state.value.attachments.single()
        assertSame(part, displayed.part)
        assertEquals("fixture.pdf", displayed.filename)
        assertEquals("application/pdf", displayed.mimeType)
    }
}

private class JvmAttachmentMapper : AttachmentViewInfoMapper<MailPart> {
    override fun AttachmentViewInfoMapper.AttachmentMetadata<MailPart>.toUiItem(
        encrypted: Boolean,
    ): AttachmentUiItem<MailPart> = AttachmentUiItem.File(
        id = AttachmentUri(uri),
        filename = filename,
        formattedSize = "12 B",
        size = getSize(),
        mimeType = getMimeType(),
        part = getPart(),
        downloaded = isContentAvailable,
        encrypted = encrypted,
    )

    override fun AttachmentUiItem<MailPart>.toDomainItem(): AttachmentViewInfoMapper.AttachmentMetadata<MailPart> =
        error("Unused by this reader path")
}
