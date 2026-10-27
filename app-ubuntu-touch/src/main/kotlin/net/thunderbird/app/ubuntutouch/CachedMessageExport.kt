package net.thunderbird.app.ubuntutouch

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import net.thunderbird.feature.account.AccountId

/** A consistent exact-byte cached .eml lease. The transfer owner must retain it until collection/finalization. */
internal class CachedMessageExport(private val database: MailDatabase, private val exportRoot: Path) {
    @Suppress("TooGenericExceptionCaught") // Clean up this lease on any failure, preserving the cause.
    fun prepare(account: AccountId, folder: String, uid: String, checkActive: () -> Unit = {}): PreparedAttachment {
        checkActive()
        var target: AttachmentTarget? = null
        try {
            val size = database.transaction {
                val snapshot = database.query(
                    """SELECT m.rowid,length(m.mime) FROM messages m JOIN folders f ON f.id=m.folder
                        WHERE f.account=? AND f.server_id=? AND m.uid=?
                        AND EXISTS(SELECT 1 FROM flags WHERE folder=m.folder AND uid=m.uid
                        AND flag='X_DOWNLOADED_FULL')""",
                    account.toString(),
                    folder,
                    uid,
                ) { getLong(0) to getLong(1) }.singleOrNull() ?: error("Complete cached message required for export")
                val owned = AttachmentTarget.create(exportRoot, "message.eml").also { target = it }
                owned.open().use { output ->
                    var offset = 0L
                    while (offset < snapshot.second) {
                        checkActive()
                        val count = minOf(EXPORT_PAGE_BYTES.toLong(), snapshot.second - offset).toInt()
                        val bytes = database.query(
                            "SELECT substr(mime,?,?) FROM messages WHERE rowid=?",
                            offset + 1,
                            count,
                            snapshot.first,
                        ) { getBlob(0) }.single()
                        check(bytes.size == count) { "Cached source changed during export" }
                        output.write(bytes)
                        offset += bytes.size
                    }
                }
                checkActive()
                FileChannel.open(owned.file, StandardOpenOption.WRITE).use { it.force(true) }
                snapshot.second
            }
            checkActive()
            return PreparedAttachment(checkNotNull(target), "message.eml", "message/rfc822", size)
        } catch (failure: Throwable) {
            try {
                target?.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
}

private const val EXPORT_PAGE_BYTES = 32768
