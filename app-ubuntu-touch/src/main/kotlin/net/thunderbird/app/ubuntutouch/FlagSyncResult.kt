package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException
import net.thunderbird.feature.account.AccountId

internal data class FlagSyncResult(val completedFolders: Int, val failedFolders: Int) {
    fun notice(): String = if (completedFolders + failedFolders == 0) {
        "No queued flag changes to sync."
    } else {
        "Synced $completedFolders changed folders; $failedFolders still need attention. Unsynced changes remain queued."
    }
}

/** Snapshot the chosen scope, isolate folder failures, and stop before the next folder on cancellation. IO only. */
// Report folder failures without exposing server errors or abandoning other accounts.
@Suppress("TooGenericExceptionCaught")
internal fun syncPendingFlagFolders(
    accounts: List<AccountId>,
    operation: MailOperation,
    folders: (AccountId) -> List<String>,
    sync: (AccountId, String, MailOperation) -> Boolean,
): FlagSyncResult {
    operation.ensureActive()
    val targets = accounts.distinct().flatMap { account -> folders(account).distinct().map { account to it } }
    var completed = 0
    var failed = 0
    for ((account, folder) in targets) {
        operation.ensureActive()
        val applied = try {
            sync(account, folder, operation)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            operation.ensureActive()
            false
        }
        operation.ensureActive()
        if (applied) completed++ else failed++
    }
    return FlagSyncResult(completed, failed)
}

internal fun LiveMailEvent?.syncQueuedFlags(
    repository: LiveMailRepository,
    state: LiveMailState,
    operation: MailOperation?,
): FlagSyncResult? = if (this == LiveMailEvent.SyncQueuedFlags) {
    val accounts = if (state.searchScope == CachedSearchScope.ALL_ACCOUNTS) {
        state.accounts.map { it.id }
    } else {
        listOf(checkNotNull(state.account))
    }
    repository.syncQueuedFlags(accounts, checkNotNull(operation))
} else {
    null
}

internal fun LiveMailState.withFlagSyncResult(result: FlagSyncResult?): LiveMailState =
    if (result == null) this else copy(notice = result.notice())

internal fun foregroundMailNotice(operation: MailOperation?, loaded: LiveMailState, action: LiveMailEvent?): String? =
    if (operation?.cancellationRequested == true) {
        "Operation stopped. Cached mail is retained."
    } else if (action == LiveMailEvent.SyncQueuedFlags) {
        loaded.notice
    } else {
        null
    }

internal fun LiveMailEvent?.isLocalMailWrite(): Boolean = this != null && this != LiveMailEvent.SyncQueuedFlags
