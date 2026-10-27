package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Body
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.mail.internet.RawDataBody
import java.io.OutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import net.thunderbird.feature.account.AccountId

internal data class AttachmentReference(
    val account: AccountId,
    val folder: String,
    val uid: String,
    val fingerprint: String,
    val ordinal: Int,
) {
    override fun toString(): String = "AttachmentReference(redacted)"
}

internal data class CachedAttachment(
    val reference: AttachmentReference,
    val displayName: String,
    val mimeType: String,
) {
    override fun toString(): String = "CachedAttachment(redacted)"
}

/** The caller keeps this lease until its receiver/transfer has finished, then closes it. */
internal class PreparedAttachment(
    private val target: AttachmentTarget,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
) : AutoCloseable {
    val file: Path get() = target.file
    override fun close() = target.close()
    override fun toString(): String = "PreparedAttachment(redacted)"
}

/** Local cached access only. Exporting a file does not itself open/share it with another application. */
internal class CachedAttachmentStore(
    private val database: MailDatabase,
    private val exportRoot: Path,
    private val maximumRawBytes: Long = DEFAULT_RAW_LIMIT,
    private val maximumDecodedBytes: Long = DEFAULT_DECODED_LIMIT,
) {
    init {
        require(maximumRawBytes > 0 && maximumDecodedBytes > 0)
    }

    fun list(account: AccountId, folder: String, uid: String): List<CachedAttachment> {
        val raw = snapshot(account, folder, uid)
        val fingerprint = fingerprint(raw)
        return withParsedCachedMime(raw) { message ->
            attachmentParts(message).mapIndexed { index, part ->
                describe(AttachmentReference(account, folder, uid, fingerprint, index), part)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Release a provisional export on every failure; rethrow unchanged.
    fun prepare(
        account: AccountId,
        folder: String,
        uid: String,
        reference: AttachmentReference,
        checkActive: () -> Unit = {},
    ): PreparedAttachment {
        require(reference.account == account && reference.folder == folder && reference.uid == uid)
        checkActive()
        val raw = snapshot(account, folder, uid)
        check(fingerprint(raw) == reference.fingerprint) { "Cached message changed; reload attachments" }
        var target: AttachmentTarget? = null
        try {
            val result = withParsedCachedMime(raw) { message ->
                val part = attachmentParts(message).getOrNull(reference.ordinal) ?: error("Attachment absent")
                val info = describe(reference, part)
                checkActive()
                val body = checkNotNull(part.body) { "Attachment body absent" }
                if (body is RawDataBody) {
                    require(body.encoding?.lowercase(Locale.ROOT) in SUPPORTED_ENCODINGS) {
                        "Unsupported attachment encoding"
                    }
                }
                val owned = AttachmentTarget.create(exportRoot, info.displayName).also { target = it }
                val size = owned.open().use { output -> exportBody(body, output, checkActive) }
                info to size
            }
            checkActive()
            return PreparedAttachment(
                checkNotNull(target),
                result.first.displayName,
                result.first.mimeType,
                result.second,
            )
        } catch (failure: Throwable) {
            try {
                target?.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun snapshot(account: AccountId, folder: String, uid: String): ByteArray = database.query(
        """SELECT m.mime FROM messages m JOIN folders f ON f.id=m.folder
            WHERE f.account=? AND f.server_id=? AND m.uid=? AND length(m.mime)<=?
            AND EXISTS(SELECT 1 FROM flags WHERE folder=m.folder AND uid=m.uid AND flag='X_DOWNLOADED_FULL')""",
        account.toString(),
        folder,
        uid,
        maximumRawBytes,
    ) { getBlob(0) }.singleOrNull() ?: error("A complete cached message within the attachment size limit is required")

    private fun describe(reference: AttachmentReference, part: Part): CachedAttachment {
        val info = attachmentInfo(part)
        return CachedAttachment(reference, info.displayName, info.mimeType)
    }

    private fun exportBody(body: Body, output: OutputStream, checkActive: () -> Unit): Long {
        val bounded = BoundedAttachmentOutput(output, maximumDecodedBytes, checkActive)
        if (body is Message) {
            // Message.getInputStream() is unsupported. Serialize the attached RFC822 message itself.
            body.writeTo(bounded)
        } else {
            MimeUtility.decodeBody(body).use { it.copyTo(bounded) }
        }
        checkActive()
        bounded.flush()
        return bounded.size
    }
}

private const val DEFAULT_RAW_LIMIT = 96L * 1024 * 1024
private const val DEFAULT_DECODED_LIMIT = 64L * 1024 * 1024
private fun fingerprint(raw: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

private val SUPPORTED_ENCODINGS = setOf("7bit", "8bit", "binary", "base64", "quoted-printable")

private class BoundedAttachmentOutput(
    private val output: OutputStream,
    private val limit: Long,
    private val checkActive: () -> Unit,
) : OutputStream() {
    var size = 0L
        private set

    override fun write(value: Int) {
        reserve(1)
        output.write(value)
        size++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length >= 0)
        reserve(length)
        output.write(bytes, offset, length)
        size += length
    }

    private fun reserve(count: Int) {
        checkActive()
        check(size <= limit - count) { "Attachment exceeds the decoded size limit" }
    }

    override fun flush() = output.flush()
}
