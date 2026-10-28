package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.internet.MimeMessage
private const val HEADER_LIMIT = 65536

/** Read only a bounded header snapshot; never parse a body or create MIME temporary files. */
internal fun filingSubject(database: MailDatabase, folder: Long, uid: String): String? {
    val raw = database.query(
        "SELECT substr(mime,1,?) FROM messages WHERE folder=? AND uid=?",
        HEADER_LIMIT,
        folder,
        uid,
    ) { getBlob(0) }.single()
    val text = raw.toString(Charsets.UTF_8)
    val end = text.indexOf("\r\n\r\n").takeIf { it >= 0 }?.plus(4)
        ?: text.indexOf("\n\n").takeIf { it >= 0 }?.plus(2)
        ?: return null
    val headers = text.substring(0, end).replace("\r\n", "\n")
        .replace(Regex("\n[ \t]+"), " ")
    val subject = headers.lineSequence().firstOrNull {
        it.substringBefore(':').equals("Subject", ignoreCase = true) && ':' in it
    }?.substringAfter(':')?.trim()
    return subject?.let { MimeMessage().apply { setHeader("Subject", it) }.subject.orEmpty() }.orEmpty()
}
