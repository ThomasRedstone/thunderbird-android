package net.thunderbird.app.ubuntutouch

import net.thunderbird.feature.account.AccountId

internal sealed interface SentCopyResult {
    data class Saved(val uid: String?) : SentCopyResult
    data object Missing : SentCopyResult
}

internal fun interface SentCopyTransport {
    fun save(account: AccountId, id: String, claim: SentCopyClaim, operation: MailOperation?): SentCopyResult
}

internal class SessionSentCopyTransport(private val sessions: ForegroundMailSessionFactory) : SentCopyTransport {
    override fun save(account: AccountId, id: String, claim: SentCopyClaim, operation: MailOperation?): SentCopyResult =
        sessions.open(account, operation).use {
            it.saveSentCopy(claim.folder, id, claim.mime, claim.allowAppend, claim.messageId)
        }
}

internal class SentCopyFiler(private val store: SentCopyStore, private val transport: SentCopyTransport) {
    fun folders(account: AccountId) = store.folders(account)
    fun target(account: AccountId) = store.target(account)
    fun configure(account: AccountId, folder: String?) = store.configure(account, folder)
    fun status(account: AccountId, id: String) = store.status(account, id)

    fun file(
        account: AccountId,
        id: String,
        operation: MailOperation? = null,
        acknowledge: Boolean = false,
    ): SentCopy? {
        val claim = if (operation?.cancellationRequested == true) null else store.claim(account, id, acknowledge)
        if (claim == null) return store.status(account, id)
        val result = try {
            operation?.ensureActive()
            transport.save(account, id, claim, operation)
        } catch (_: Exception) {
            // Even a disconnected APPEND can have succeeded. Search before any explicitly acknowledged retry.
            SentCopyResult.Missing
        }
        store.finish(account, id, result)
        return store.status(account, id)
    }
}
