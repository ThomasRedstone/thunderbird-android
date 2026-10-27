package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal data class MailFolder(val id: String, val name: String)
internal data class MailSummary(val id: String, val subject: String, val sender: String, val unread: Boolean)
internal data class MailContent(val summary: MailSummary, val body: String, val complete: Boolean)

/** Local reads never request credentials or initiate network activity. Blocking methods require an IO caller. */
internal interface LiveMailRepository {
    fun folders(account: AccountId): List<MailFolder>
    fun messages(account: AccountId, folder: String): List<MailSummary>
    fun content(account: AccountId, folder: String, uid: String): MailContent
    fun refresh(account: AccountId, folder: String?)
    fun download(account: AccountId, folder: String, uid: String)
}

internal class PersistentLiveMailRepository(
    private val database: MailDatabase,
    private val sessions: ForegroundMailSessionFactory,
) : LiveMailRepository {
    override fun folders(account: AccountId): List<MailFolder> =
        database.query("SELECT server_id,name FROM folders WHERE account=? ORDER BY name", account.toString()) {
            MailFolder(getText(0), getText(1))
        }

    override fun messages(account: AccountId, folder: String): List<MailSummary> {
        val cached = storage(account).getFolder(folder)
        return cached.getAllMessagesAndEffectiveDates().entries.sortedByDescending { it.value ?: 0L }.map { (uid, _) ->
            summary(cached, uid)
        }
    }

    override fun content(account: AccountId, folder: String, uid: String): MailContent {
        val cached = storage(account).getFolder(folder)
        val message = checkNotNull(cached.readMessage(uid)) { "Message absent from cache" }
        val part = MimeUtility.findFirstPartByMimeType(message, "text/plain")
        return MailContent(
            summary(cached, uid),
            part?.let { MessageExtractor.getTextFromPart(it) }
                ?: "No cached plain-text body. HTML viewing is not available yet.",
            Flag.X_DOWNLOADED_FULL in cached.getMessageFlags(uid),
        )
    }

    override fun refresh(account: AccountId, folder: String?) {
        sessions.open(account).use {
            it.refreshFolders()
            if (folder != null && folder in storage(account).getFolderServerIds()) it.sync(folder)
        }
    }

    override fun download(account: AccountId, folder: String, uid: String) {
        sessions.open(account).use { it.downloadMessage(folder, uid) }
    }

    private fun storage(account: AccountId) = PersistentBackendStorage(database, account.toString())

    private fun summary(folder: PersistentBackendFolder, uid: String): MailSummary {
        val message = checkNotNull(folder.readMessage(uid)) { "Message absent from cache" }
        return MailSummary(
            uid,
            message.subject ?: "(No subject)",
            message.from?.joinToString { it.toString() }.orEmpty(),
            Flag.SEEN !in folder.getMessageFlags(uid),
        )
    }
}
