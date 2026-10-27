package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.MimeMessage
import java.io.ByteArrayOutputStream
import java.util.Date
import net.thunderbird.core.common.mail.Flag

@Suppress("TooManyFunctions") // The existing backend contract requires these operations.
internal class PersistentBackendFolder(
    private val database: MailDatabase,
    account: String,
    val id: Long,
) : BackendFolder {
    private val metadata = MailMetadata(database, account, id)
    override val name: String
        get() = database.query("SELECT name FROM folders WHERE id=?", id) { getText(0) }.single()
    override val visibleLimit: Int = Int.MAX_VALUE

    override fun getMessageServerIds(): Set<String> =
        database.query("SELECT uid FROM messages WHERE folder=?", id) { getText(0) }.toSet()

    override fun getAllMessagesAndEffectiveDates(): Map<String, Long?> =
        database.query("SELECT uid,effective_date FROM messages WHERE folder=?", id) {
            getText(0) to if (isNull(1)) null else getLong(1)
        }.toMap()

    override fun destroyMessages(messageServerIds: List<String>) = database.transaction {
        messageServerIds.forEach { database.execute("DELETE FROM messages WHERE folder=? AND uid=?", id, it) }
    }

    override fun clearAllMessages() = database.execute("DELETE FROM messages WHERE folder=?", id)
    override fun getMoreMessages(): MoreMessages =
        metadata.text("moreMessages")?.let(MoreMessages::valueOf) ?: MoreMessages.UNKNOWN
    override fun setMoreMessages(moreMessages: MoreMessages) = metadata.setText("moreMessages", moreMessages.name)
    override fun setLastChecked(timestamp: Long) = metadata.setNumber("lastChecked", timestamp)
    override fun setStatus(status: String?) = metadata.setText("status", status)
    override fun isMessagePresent(messageServerId: String): Boolean =
        database.query("SELECT 1 FROM messages WHERE folder=? AND uid=?", id, messageServerId) {
            getLong(0)
        }.isNotEmpty()

    override fun getMessageFlags(messageServerId: String): Set<Flag> {
        check(isMessagePresent(messageServerId)) { "Message absent from cache" }
        return database.query("SELECT flag FROM flags WHERE folder=? AND uid=?", id, messageServerId) {
            Flag.valueOf(getText(0))
        }.toSet()
    }

    override fun setMessageFlag(messageServerId: String, flag: Flag, value: Boolean) = database.transaction {
        check(isMessagePresent(messageServerId)) { "Message absent from cache" }
        if (value) {
            database.execute(
                "INSERT OR IGNORE INTO flags(folder,uid,flag) VALUES(?,?,?)",
                id,
                messageServerId,
                flag.name,
            )
        } else {
            database.execute("DELETE FROM flags WHERE folder=? AND uid=? AND flag=?", id, messageServerId, flag.name)
        }
    }

    override suspend fun saveMessage(message: Message, downloadState: MessageDownloadState) {
        val uid = checkNotNull(message.uid)
        val raw = ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()
        val flags = message.flags.toMutableSet().apply {
            when (downloadState) {
                MessageDownloadState.ENVELOPE -> Unit
                MessageDownloadState.PARTIAL -> add(Flag.X_DOWNLOADED_PARTIAL)
                MessageDownloadState.FULL -> add(Flag.X_DOWNLOADED_FULL)
            }
        }
        database.transaction {
            database.execute(
                """INSERT INTO messages(folder,uid,mime,effective_date) VALUES(?,?,?,?)
                    ON CONFLICT(folder,uid) DO UPDATE SET mime=excluded.mime,effective_date=excluded.effective_date""",
                id,
                uid,
                raw,
                (message.sentDate ?: message.internalDate)?.time,
            )
            database.execute("DELETE FROM flags WHERE folder=? AND uid=?", id, uid)
            flags.forEach { flag ->
                database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,?,?)", id, uid, flag.name)
            }
        }
    }

    fun readMessage(uid: String): MimeMessage? {
        val raw = database.query("SELECT mime FROM messages WHERE folder=? AND uid=?", id, uid) { getBlob(0) }
            .singleOrNull() ?: return null
        return MimeMessage.parseMimeMessage(raw.inputStream(), true).apply {
            this.uid = uid
            getMessageFlags(uid).forEach { setFlag(it, true) }
        }
    }

    override fun getOldestMessageDate(): Date? = database.query(
        "SELECT MIN(effective_date) FROM messages WHERE folder=?",
        id,
    ) { if (isNull(0)) null else Date(getLong(0)) }.single()

    override fun getFolderExtraString(name: String): String? = metadata.text("extra:$name")
    override fun setFolderExtraString(name: String, value: String?) = metadata.setText("extra:$name", value)
    override fun getFolderExtraNumber(name: String): Long? = metadata.number("extra:$name")
    override fun setFolderExtraNumber(name: String, value: Long) = metadata.setNumber("extra:$name", value)
}
