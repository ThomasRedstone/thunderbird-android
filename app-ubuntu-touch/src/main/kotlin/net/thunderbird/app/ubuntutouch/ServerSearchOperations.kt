package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.MessageDownloadState
import net.thunderbird.feature.account.AccountId

internal interface ServerSearchOperations {
    fun search(account: AccountId, folder: String, query: ServerMailQuery, operation: MailOperation): ServerMailResults
    fun open(results: ServerMailResults, uid: String, operation: MailOperation): MailContent
}

/** Explicit network operations only. Results never authorize a cache write without fresh owner/generation checks. */
internal class PersistentServerSearchOperations(
    private val database: MailDatabase,
    private val accounts: UtAccountManager,
    private val sessions: ForegroundMailSessionFactory,
) : ServerSearchOperations {
    override fun search(
        account: AccountId,
        folder: String,
        query: ServerMailQuery,
        operation: MailOperation,
    ): ServerMailResults = sessions.open(account, operation).use { it.searchServer(folder, query) }

    override fun open(results: ServerMailResults, uid: String, operation: MailOperation): MailContent {
        val owner = checkNotNull(accounts.findById(results.account))
        check(mailCacheBinding(owner) == results.binding) { "Account changed; search again" }
        require(results.messages.any { it.id == uid })
        sessions.open(results.account, operation).use { session ->
            session.openServerResult(results, uid) { message ->
                val cache = PersistentBackendStorage(database, owner.id.toString(), results.binding)
                    .getFolder(results.folder)
                cache.saveMessageChecked(message, MessageDownloadState.FULL) {
                    operation.ensureActive()
                    val currentFolder = PersistentBackendStorage(database, owner.id.toString())
                        .getFolder(results.folder)
                    check(currentFolder.id == cache.id)
                    val generation = cache.getFolderExtraNumber("imapUidValidity")
                    val binding = cache.getFolderExtraString(CACHE_BINDING_KEY)
                    if (generation == null && binding == null && cache.getMessageServerIds().isEmpty()) {
                        cache.setFolderExtraNumber("imapUidValidity", results.generation)
                        cache.setFolderExtraString(CACHE_BINDING_KEY, results.binding)
                    } else {
                        check(generation == results.generation && binding == results.binding) {
                            "Cached mailbox changed; refresh and search again"
                        }
                    }
                }
            }
        }
        lateinit var content: MailContent
        accounts.withCurrentAccount(owner) {
            operation.ensureActive()
            content = PersistentLiveMailRepository(database, sessions).content(owner.id, results.folder, uid)
        }
        return content
    }
}
