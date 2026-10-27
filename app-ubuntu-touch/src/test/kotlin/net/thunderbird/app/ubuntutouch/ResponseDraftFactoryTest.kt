package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResponseDraftFactoryTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `reply uses Reply-To and quotes Unicode with safe threading`() = withFactory { testSubject ->
        val seed = testSubject.seed(
            message(),
            "Hello λ\r\nSecond line",
            listOf("self@example.invalid"),
            ResponseMode.REPLY,
        )
        assertThat(Address.parse(seed.to).map { it.address }).isEqualTo(listOf("reply@example.invalid"))
        assertThat(seed.cc).isEqualTo("")
        assertThat(seed.subject).isEqualTo("Re: Fixture")
        assertThat(seed.body).isEqualTo("\n\n> Hello λ\n> Second line")
        assertThat(seed.inReplyTo).isEqualTo("<parent@example.invalid>")
        assertThat(seed.references).isEqualTo("<root@example.invalid> <parent@example.invalid>")
    }

    @Test
    fun `reply all excludes configured identities deduplicates and never copies Bcc`() = withFactory { testSubject ->
        val seed = testSubject.seed(
            message(),
            "Body",
            listOf("self@example.invalid", "alias@example.invalid"),
            ResponseMode.REPLY_ALL,
        )
        assertThat(Address.parse(seed.to).map { it.address.lowercase() })
            .isEqualTo(listOf("reply@example.invalid", "sender@example.invalid", "other@example.invalid"))
        assertThat(Address.parse(seed.cc).map { it.address }).isEqualTo(listOf("copy@example.invalid"))
        assertThat((seed.to + seed.cc).contains("blind@example.invalid")).isFalse()
        assertThat(seed.toString()).isEqualTo("ResponseSeed(redacted)")
    }

    @Test
    fun `self reply uses original recipients and retains subject prefix`() = withFactory { testSubject ->
        val source = MimeMessage.parseMimeMessage(
            (
                "From: self@example.invalid\r\nTo: self@example.invalid, other@example.invalid\r\n" +
                    "Subject: re: Existing\r\n\r\nBody"
                ).byteInputStream(),
            true,
        )
        val seed = testSubject.seed(source, "Body", listOf("self@example.invalid"), ResponseMode.REPLY)
        assertThat(Address.parse(seed.to).single().address).isEqualTo("other@example.invalid")
        assertThat(seed.subject).isEqualTo("re: Existing")
        assertThat(seed.inReplyTo).isEqualTo("")
    }

    @Test
    fun `forward includes visible source context but no recipients threading or Bcc`() = withFactory { testSubject ->
        val seed = testSubject.seed(message(), "Source body", listOf("self@example.invalid"), ResponseMode.FORWARD)
        assertThat(seed.to).isEqualTo("")
        assertThat(seed.cc).isEqualTo("")
        assertThat(seed.subject).isEqualTo("Fwd: Fixture")
        assertThat(seed.inReplyTo).isEqualTo("")
        assertThat(seed.references).isEqualTo("")
        assertThat(seed.body.contains("From: sender@example.invalid")).isTrue()
        assertThat(seed.body.endsWith("Source body")).isTrue()
        assertThat(seed.body.contains("blind@example.invalid")).isFalse()
    }

    @Test
    fun `thread ids are safe and bounded preserving root and parent`() = withFactory { testSubject ->
        val source = message()
        source.messageId = "malformed header"
        assertThat(testSubject.seed(source, "", emptyList(), ResponseMode.REPLY).inReplyTo).isEqualTo("")
        source.messageId = "<parent@example.invalid>"
        source.setHeader("References", (0..100).joinToString(" ") { "<id$it@example.invalid>" })
        val seed = testSubject.seed(source, "", emptyList(), ResponseMode.REPLY)
        assertThat(seed.references.startsWith("<id0@example.invalid>")).isTrue()
        assertThat(seed.references.endsWith("<parent@example.invalid>")).isTrue()
        assertThat(seed.references.split(' ').size).isEqualTo(10)
        source.setHeader("References", "<root@example.invalid> <parent@example.invalid> <other@example.invalid>")
        assertThat(testSubject.seed(source, "", emptyList(), ResponseMode.REPLY).references)
            .isEqualTo("<root@example.invalid> <other@example.invalid> <parent@example.invalid>")
    }

    @Test
    fun `list post is used before sender without copying mailto query recipients`() = withFactory { testSubject ->
        val source = MimeMessage.parseMimeMessage(
            (
                "From: sender@example.invalid\r\n" +
                    "List-Post: <mailto:list+tag@example.invalid?bcc=hidden@example.invalid>, " +
                    "<mailto:alternative@example.invalid>\r\n" +
                    "To: self@example.invalid\r\nSubject: List\r\n\r\nBody"
                ).byteInputStream(),
            true,
        )
        val seed = testSubject.seed(source, "Body", listOf("self@example.invalid"), ResponseMode.REPLY)
        assertThat(Address.parse(seed.to).single().address).isEqualTo("list+tag@example.invalid")
        assertThat(seed.to.contains("hidden")).isFalse()
    }

    @Test
    fun `HTML plus text attachment never exposes the attachment as a reply body`() = withFactory {
        val source = MimeMessage.parseMimeMessage(
            (
                "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: text/html\r\n\r\n" +
                    "<p>HTML body</p>\r\n--b\r\nContent-Type: text/plain\r\n" +
                    "Content-Disposition: attachment; filename=private.txt\r\n\r\nPrivate attachment\r\n--b--\r\n"
                )
                .byteInputStream(),
            true,
        )
        assertThat(plainTextBody(source)).isEqualTo(null)
    }

    @Test
    fun `multiple inline text parts keep order and exclude attachments`() = withFactory {
        val source = MimeMessage.parseMimeMessage(
            (
                "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nFirst\r\n" +
                    "--b\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=file.txt\r\n\r\n" +
                    "Attachment\r\n--b\r\nContent-Type: text/plain\r\n\r\nSecond\r\n--b--\r\n"
                ).byteInputStream(),
            true,
        )
        assertThat(plainTextBody(source)).isEqualTo("First\nSecond")
    }

    private fun withFactory(block: (ResponseDraftFactory) -> Unit) {
        val previous = BinaryTempFileBody.getTempDirectory()
        try {
            BinaryTempFileBody.setTempDirectory(directory.newFolder("mime"))
            MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { block(ResponseDraftFactory(it)) }
        } finally {
            BinaryTempFileBody.setTempDirectory(previous)
        }
    }

    private fun message() = MimeMessage.parseMimeMessage(
        (
            "From: sender@example.invalid\r\nReply-To: Reply <reply@example.invalid>\r\n" +
                "To: self@example.invalid, other@example.invalid, REPLY@example.invalid\r\n" +
                "Cc: alias@example.invalid, copy@example.invalid, OTHER@example.invalid\r\n" +
                "Bcc: blind@example.invalid\r\nSubject: Fixture\r\nMessage-ID: <parent@example.invalid>\r\n" +
                "References: <root@example.invalid>\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nBody"
            )
            .byteInputStream(),
        true,
    )
}
