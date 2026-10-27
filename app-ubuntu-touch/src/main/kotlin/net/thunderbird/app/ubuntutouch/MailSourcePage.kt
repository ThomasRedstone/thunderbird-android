package net.thunderbird.app.ubuntutouch

import net.thunderbird.feature.account.AccountId

/** A bounded byte page, not a MIME projection. All cached bytes remain reachable through subsequent pages. */
internal class MailSourcePage(val offset: Long, val totalBytes: Long, val complete: Boolean, bytes: ByteArray) {
    val sizeBytes = bytes.size
    val nextOffset: Long? get() = (offset + sizeBytes).takeIf { it < totalBytes }
    val previousOffset: Long? get() = (offset - SOURCE_PAGE_BYTES).coerceAtLeast(0).takeIf { offset > 0 }

    init {
        require(offset >= 0 && totalBytes >= offset && bytes.size <= SOURCE_PAGE_BYTES)
        require(bytes.size.toLong() == minOf(SOURCE_PAGE_BYTES.toLong(), totalBytes - offset))
    }

    val text = escapedSourceBytes(bytes)

    override fun toString(): String = "MailSourcePage(redacted)"
}

/** SQLite substr bounds the returned JVM page; no MIME parser or credentials are involved. */
internal fun cachedMessageSource(
    database: MailDatabase,
    account: AccountId,
    folder: String,
    uid: String,
    offset: Long,
): MailSourcePage {
    require(offset >= 0 && offset % SOURCE_PAGE_BYTES == 0L && offset < Long.MAX_VALUE)
    return database.query(
        """SELECT substr(m.mime,?,?),length(m.mime),
            EXISTS(SELECT 1 FROM flags WHERE folder=m.folder AND uid=m.uid AND flag='X_DOWNLOADED_FULL')
            FROM messages m JOIN folders f ON f.id=m.folder
            WHERE f.account=? AND f.server_id=? AND m.uid=?
            AND (?<length(m.mime) OR (?=0 AND length(m.mime)=0))""",
        offset + 1,
        SOURCE_PAGE_BYTES,
        account.toString(),
        folder,
        uid,
        offset,
        offset,
    ) { MailSourcePage(offset, getLong(1), getLong(2) != 0L, getBlob(0)) }.singleOrNull()
        ?: error("Cached message source page unavailable")
}

/** Escapes backslashes too, so binary, non-ASCII and control bytes are unambiguous and cannot inject bidi controls. */
internal fun escapedSourceBytes(bytes: ByteArray): String = buildString {
    bytes.forEach {
        val value = it.toInt() and 0xff
        when (value) {
            '\n'.code -> append('\n')
            '\\'.code -> append("\\\\")
            in ' '.code..'~'.code -> append(value.toChar())
            else -> append("\\x").append(HEX[value shr HEX_DIGIT_BITS]).append(HEX[value and HEX_DIGIT_MASK])
        }
    }
}

internal const val SOURCE_PAGE_BYTES = 32768
private const val HEX = "0123456789abcdef"

private const val HEX_DIGIT_BITS = 4
private const val HEX_DIGIT_MASK = 15
