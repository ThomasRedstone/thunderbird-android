package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Message
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal data class MailFolder(val id: String, val name: String)
internal data class MailSummary(
    val id: String,
    val subject: String,
    val sender: String,
    val unread: Boolean,
    val starred: Boolean = false,
    val effectiveDate: Long? = null,
)
internal data class MailContent(
    val summary: MailSummary,
    val body: String,
    val complete: Boolean,
    val plainText: Boolean = true,
)

/** Local reads never request credentials or initiate network activity. Blocking methods require an IO caller. */
internal interface LiveMailRepository {
    fun respond(account: AccountId, folder: String, uid: String, mode: ResponseMode): String {
        error("Responses are unavailable")
    }
    fun changeFlag(account: AccountId, folder: String, uid: String, flag: Flag, value: Boolean) {
        error("Message actions are unavailable")
    }
    fun discardFlags(account: AccountId) {
        error("Message actions are unavailable")
    }
    fun pendingFlags(account: AccountId): Int = 0
    fun folders(account: AccountId): List<MailFolder>
    fun messages(account: AccountId, folder: String): List<MailSummary>
    fun content(account: AccountId, folder: String, uid: String): MailContent
    fun refresh(account: AccountId, folder: String?)
    fun refresh(account: AccountId, folder: String?, operation: MailOperation) = refresh(account, folder)
    fun download(account: AccountId, folder: String, uid: String)
    fun download(
        account: AccountId,
        folder: String,
        uid: String,
        operation: MailOperation,
    ) = download(account, folder, uid)
}

@Suppress("TooManyFunctions") // Repository contract plus cache projections.
internal class PersistentLiveMailRepository(
    private val database: MailDatabase,
    private val sessions: ForegroundMailSessionFactory,
) : LiveMailRepository {
    private val actions = PendingFlagStore(database)

    override fun respond(account: AccountId, folder: String, uid: String, mode: ResponseMode): String =
        ResponseDraftFactory(database).create(account, folder, uid, mode)

    override fun changeFlag(account: AccountId, folder: String, uid: String, flag: Flag, value: Boolean) =
        actions.enqueue(account, folder, uid, flag, value)

    override fun discardFlags(account: AccountId) = actions.discard(account)

    override fun pendingFlags(account: AccountId): Int = actions.list(account).size

    override fun folders(account: AccountId): List<MailFolder> =
        database.query("SELECT server_id,name FROM folders WHERE account=? ORDER BY name", account.toString()) {
            MailFolder(getText(0), getText(1))
        }

    override fun messages(account: AccountId, folder: String): List<MailSummary> {
        val cached = storage(account).getFolder(folder)
        return cached.getAllMessagesAndEffectiveDates().entries.sortedByDescending {
            it.value ?: 0L
        }.map { (uid, date) ->
            cached.withCachedMessage(uid) { message -> summary(account, folder, cached, uid, message, date) }
        }
    }

    override fun content(account: AccountId, folder: String, uid: String): MailContent {
        val cached = storage(account).getFolder(folder)
        return cached.withCachedMessage(uid) { message ->
            val text = plainTextBody(message)
            MailContent(
                summary(account, folder, cached, uid, message, cached.getAllMessagesAndEffectiveDates()[uid]),
                text ?: "No cached plain-text body. HTML viewing is not available yet.",
                Flag.X_DOWNLOADED_FULL in cached.getMessageFlags(uid),
                text != null,
            )
        }
    }

    override fun refresh(account: AccountId, folder: String?) = refresh(account, folder, MailOperation())

    override fun refresh(account: AccountId, folder: String?, operation: MailOperation) {
        sessions.open(account, operation).use {
            it.refreshFolders()
            operation.ensureActive()
            if (folder != null && folder in storage(account).getFolderServerIds()) {
                it.sync(folder)
                for (action in actions.list(account).filter { action -> action.folder == folder }) {
                    operation.ensureActive()
                    it.setFlag(folder, action.uid, action.flag, action.enabled, action.uidValidity)
                    actions.acknowledge(account, action)
                }
                // Read back acknowledged server flags, including changes made by another client.
                it.sync(folder)
            }
        }
    }

    override fun download(account: AccountId, folder: String, uid: String) =
        download(account, folder, uid, MailOperation())

    override fun download(account: AccountId, folder: String, uid: String, operation: MailOperation) {
        sessions.open(account, operation).use { it.downloadMessage(folder, uid) }
    }

    private fun storage(account: AccountId) = PersistentBackendStorage(database, account.toString())

    private fun summary(
        account: AccountId,
        name: String,
        folder: PersistentBackendFolder,
        uid: String,
        message: Message,
        effectiveDate: Long?,
    ): MailSummary {
        val flags = actions.overlay(account, name, uid, folder.getMessageFlags(uid))
        return MailSummary(
            uid,
            message.subject ?: "(No subject)",
            message.from?.joinToString { it.toString() }.orEmpty(),
            Flag.SEEN !in flags,
            Flag.FLAGGED in flags,
            effectiveDate,
        )
    }
}
