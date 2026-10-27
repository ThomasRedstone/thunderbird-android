package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal data class ComposeRequest(val account: AccountId, val id: String, val notice: String? = null)

internal data class LiveMailState(
    val accounts: List<UtMailAccount>,
    val account: AccountId? = null,
    val folders: List<MailFolder> = emptyList(),
    val folder: MailFolder? = null,
    val messages: List<MailSummary> = emptyList(),
    val content: MailContent? = null,
    val source: MailSourcePage? = null,
    val query: String = "",
    val searchScope: CachedSearchScope = CachedSearchScope.FOLDER,
    val searchHits: List<CachedMailHit> = emptyList(),
    val returnFolder: MailFolder? = null,
    val returnAccount: AccountId? = null,
    val composeRequest: ComposeRequest? = null,
    val pendingActions: Int = 0,
    val confirmDiscard: Boolean = false,
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val cancelling: Boolean = false,
    val notice: String? = null,
    val sort: MailSort = MailSort.NEWEST,
    val selection: MailSelection = MailSelection(),
) {
    val searchAllFolders: Boolean get() = searchScope != CachedSearchScope.FOLDER
    override fun toString(): String = "LiveMailState(redacted)"
}

internal sealed interface LiveMailEvent {
    data class Selection(val action: MailSelectionAction) : LiveMailEvent
    data class Sort(val sort: MailSort) : LiveMailEvent
    data class OpenAccount(val id: AccountId) : LiveMailEvent
    data class OpenFolder(val folder: MailFolder) : LiveMailEvent
    data class Search(val query: String) : LiveMailEvent {
        override fun toString(): String = "Search(redacted)"
    }
    data class SearchScope(val scope: CachedSearchScope) : LiveMailEvent
    data class OpenSearchResult(val hit: CachedMailHit) : LiveMailEvent
    data class OpenMessage(val uid: String) : LiveMailEvent
    data class Source(val offset: Long = 0) : LiveMailEvent
    data class Respond(val mode: ResponseMode) : LiveMailEvent
    data class ChangeFlag(val flag: Flag, val enabled: Boolean) : LiveMailEvent
    data object DiscardPending : LiveMailEvent
    data object ConfirmDiscard : LiveMailEvent
    data object CancelDiscard : LiveMailEvent
    data object SyncQueuedFlags : LiveMailEvent
    data object ReloadCached : LiveMailEvent
    data object Refresh : LiveMailEvent
    data object Download : LiveMailEvent
    data object Cancel : LiveMailEvent
    data object Back : LiveMailEvent
}

internal class LiveMailViewModel(
    private val manager: UtAccountManager,
    private val repository: LiveMailRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val sortPreferences: MailSortPreferences? = null,
) : AutoCloseable {
    private val selectedSort = sortPreferences?.read() ?: MailSort.NEWEST
    private val scopedSorts = java.util.concurrent.ConcurrentHashMap<MailSortContext, MailSort>()
    private val mutableState = MutableStateFlow(LiveMailState(manager.findAll(), sort = selectedSort))
    val state = mutableState.asStateFlow()
    private var activeOperation: MailOperation? = null
    private var localWrite: Job? = null
    private var localWriteFailed = false

    fun event(event: LiveMailEvent) {
        val current = state.value.forMailEvent(event)
        if (event == LiveMailEvent.Cancel) {
            cancel(current)
            return
        }
        if (current.busy ||
            handleLocalMailAction(event, current, { mutableState.value = it }, { load(current, action = event) }) ||
            handleActions(event, current)
        ) {
            return
        }
        when (event) {
            is LiveMailEvent.Sort -> if (current.isMessageList()) {
                scopedSorts[checkNotNull(current.sortContext())] = event.sort
                load(current.copy(sort = event.sort), action = event)
            }

            is LiveMailEvent.OpenAccount -> {
                require(manager.findById(event.id) != null)
                load(LiveMailState(manager.findAll(), account = event.id, sort = selectedSort))
            }

            is LiveMailEvent.OpenFolder -> {
                require(event.folder in current.folders)
                load(
                    current.copy(
                        folder = event.folder,
                        messages = emptyList(),
                        content = null,
                        query = "",
                        searchScope = CachedSearchScope.FOLDER,
                        searchHits = emptyList(),
                        returnFolder = null,
                        returnAccount = null,
                    ),
                )
            }

            is LiveMailEvent.OpenMessage -> {
                require(!current.searchAllFolders && current.messages.any { it.id == event.uid })
                load(current, uid = event.uid)
            }

            LiveMailEvent.Refresh -> load(current, network = true)

            LiveMailEvent.Download -> load(current, uid = current.content?.summary?.id, download = true)

            LiveMailEvent.Back -> back(current)

            else -> Unit
        }
    }

    private fun back(current: LiveMailState) {
        val restored = current.copy(
            content = null,
            account = current.returnAccount ?: current.account,
            folder = current.returnFolder ?: current.folder,
            returnFolder = null,
            returnAccount = null,
            notice = null,
        )
        when {
            current.content != null && restored.account != current.account -> load(restored)

            current.content != null -> mutableState.value = restored

            current.folder != null -> mutableState.value = current.copy(
                folder = null,
                messages = emptyList(),
                notice = null,
                query = "",
                searchScope = CachedSearchScope.FOLDER,
                searchHits = emptyList(),
            )

            else -> mutableState.value = LiveMailState(manager.findAll(), sort = selectedSort)
        }
    }

    private fun cancel(current: LiveMailState) {
        if (current.busy && current.canCancel) {
            activeOperation?.cancelIn(scope)
            mutableState.value =
                current.copy(cancelling = true, notice = "Cancelling… Waiting for the operation to stop.")
        }
    }

    private fun handleActions(event: LiveMailEvent, current: LiveMailState): Boolean {
        when (event) {
            LiveMailEvent.ReloadCached -> load(current.forCachedReload(), action = event)

            is LiveMailEvent.Search -> search(current, event.query)

            is LiveMailEvent.SearchScope -> if (current.folder != null && current.content == null) {
                load(
                    current.copy(searchScope = event.scope, messages = emptyList(), searchHits = emptyList()),
                )
            }

            is LiveMailEvent.OpenSearchResult -> openSearchResult(current, event.hit)

            is LiveMailEvent.Respond,
            is LiveMailEvent.ChangeFlag,
            -> current.content?.let { load(current, uid = it.summary.id, action = event) }

            LiveMailEvent.DiscardPending -> mutableState.value = current.copy(confirmDiscard = true)

            LiveMailEvent.CancelDiscard -> mutableState.value = current.copy(confirmDiscard = false)

            LiveMailEvent.ConfirmDiscard -> if (current.canDiscardAccountChanges()) {
                load(current.copy(confirmDiscard = false), uid = current.content?.summary?.id, action = event)
            }

            else -> return false
        }
        return true
    }

    private fun search(current: LiveMailState, query: String) {
        if (current.folder == null || current.content != null) return
        if (query.length > MAX_SEARCH_LENGTH) {
            mutableState.value = current.copy(notice = "Search must be at most $MAX_SEARCH_LENGTH characters.")
        } else {
            load(current.copy(query = query, messages = emptyList(), searchHits = emptyList()))
        }
    }

    private fun openSearchResult(current: LiveMailState, hit: CachedMailHit) {
        require(current.searchAllFolders && current.content == null)
        require(hit in current.searchHits && manager.findById(hit.account) != null)
        require(current.searchScope == CachedSearchScope.ALL_ACCOUNTS || hit.account == current.account)
        load(
            current.copy(
                account = hit.account,
                folder = hit.folder,
                returnAccount = current.account,
                returnFolder = current.folder,
            ),
            uid = hit.message.id,
        )
    }

    private fun load(
        initial: LiveMailState,
        uid: String? = null,
        network: Boolean = false,
        download: Boolean = false,
        action: LiveMailEvent? = null,
    ) {
        if (initial.account == null) return
        val searching = initial.searchingFor(action)
        val transport = network || download || action == LiveMailEvent.SyncQueuedFlags
        val operation = if (transport || searching) MailOperation() else null
        activeOperation = operation
        mutableState.value = initial.copy(busy = true, canCancel = operation != null, cancelling = false, notice = null)
        if (action.isLocalMailWrite()) localWriteFailed = false
        val work = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (action is LiveMailEvent.Source) {
                        repository.readSource(initial, action)
                    } else {
                        perform(initial, uid, network, download, action, operation)
                    }
                }
                mutableState.value = loaded.copy(
                    canCancel = false,
                    cancelling = false,
                    notice = foregroundMailNotice(operation, loaded, action),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (action.isLocalMailWrite()) localWriteFailed = true
                val retained =
                    withContext(Dispatchers.IO) {
                        if (keepInitialOnFailure(action, operation)) {
                            initial
                        } else {
                            runCatching { readCache(initial, uid) }.getOrDefault(initial)
                        }
                    }
                mutableState.value = retained.copy(
                    busy = false,
                    canCancel = false,
                    cancelling = false,
                    notice = if (operation?.cancellationRequested == true) {
                        "Cancelled. Cached mail is retained."
                    } else {
                        failureNotice(action)
                    },
                )
            } finally {
                if (activeOperation === operation) activeOperation = null
            }
        }
        if (action.isLocalMailWrite()) localWrite = work
    }

    private fun perform(
        initial: LiveMailState,
        uid: String?,
        network: Boolean,
        download: Boolean,
        action: LiveMailEvent?,
        operation: MailOperation?,
    ): LiveMailState {
        val account = checkNotNull(initial.account)
        operation?.ensureActive()
        val replay = action.syncQueuedFlags(repository, initial, operation)
        if (action is LiveMailEvent.ChangeFlag && uid != null) {
            repository.changeFlag(
                account,
                checkNotNull(initial.folder).id,
                uid,
                action.flag,
                action.enabled,
            )
        }
        if (action is LiveMailEvent.Selection && action.action is MailSelectionAction.ApplyFlag) {
            repository.changeFlags(initial.selection.keys.toList(), action.action.flag, action.action.enabled)
        }
        if (action is LiveMailEvent.Sort) sortPreferences?.save(action.sort, initial.sortContext())
        if (action == LiveMailEvent.ConfirmDiscard) repository.discardFlags(account)
        if (network) repository.refresh(account, initial.folder?.id, checkNotNull(operation))
        if (download && uid != null) {
            repository.download(account, checkNotNull(initial.folder).id, uid, checkNotNull(operation))
        }
        operation?.ensureActive()
        val prepared = if (action is LiveMailEvent.Respond && uid != null) {
            val id = repository.respond(account, checkNotNull(initial.folder).id, uid, action.mode)
            val notice = if (action.mode ==
                ResponseMode.FORWARD
            ) {
                "Forwarded text only. Attachments are not included."
            } else {
                null
            }
            initial.copy(composeRequest = ComposeRequest(account, id, notice))
        } else {
            initial
        }
        val loaded = readCache(prepared, uid, operation).withFlagSyncResult(replay)
        return if (action is LiveMailEvent.Selection) loaded.copy(selection = MailSelection()) else loaded
    }

    private fun readCache(initial: LiveMailState, uid: String?, operation: MailOperation? = null): LiveMailState {
        val account = checkNotNull(initial.account)
        val folders = repository.folders(account)
        val folder = initial.folder?.let { old -> folders.find { it.id == old.id } }
        return initial.copy(
            accounts = manager.findAll(),
            sort = initial.sortContext()?.let { context ->
                scopedSorts.getOrPut(context) { sortPreferences?.read(context) ?: selectedSort }
            } ?: selectedSort,
            folders = folders,
            pendingActions = if (initial.searchScope == CachedSearchScope.ALL_ACCOUNTS) {
                manager.findAll().sumOf { repository.pendingFlags(it.id) }
            } else {
                repository.pendingFlags(account)
            },
            folder = folder,
            messages = if (initial.searchAllFolders) {
                emptyList()
            } else {
                folder?.let {
                    repository.search(account, it.id, initial.query) { operation?.ensureActive() }
                }.orEmpty()
            },
            searchHits = when (initial.searchScope) {
                CachedSearchScope.FOLDER -> emptyList()

                CachedSearchScope.ACCOUNT -> repository.searchAccount(account, initial.query) {
                    operation?.ensureActive()
                }

                CachedSearchScope.ALL_ACCOUNTS -> repository.searchAccounts(
                    manager.findAll().map { it.id },
                    initial.query,
                ) { operation?.ensureActive() }
            },
            content = if (uid != null && folder != null) repository.content(account, folder.id, uid) else null,
            busy = false,
        ).sortedForDisplay().retainListedSelection()
    }

    suspend fun flushForClose(): Boolean {
        while (true) {
            val pending = localWrite
            pending?.join()
            if (pending === localWrite) break
        }
        val saved = !localWriteFailed
        // First close exposes a failed write; a subsequent explicit close may leave the unchanged cache.
        localWriteFailed = false
        return saved
    }

    override fun close() {
        activeOperation?.cancelIn(scope)
        scope.cancel()
    }
}

private fun failureNotice(action: LiveMailEvent?): String = if (action is LiveMailEvent.Sort) {
    "Unable to save sort order. Your selection applies until this mailbox closes. Try again."
} else if (action is LiveMailEvent.Source) {
    "Unable to read this source page. Cached mail is retained."
} else {
    "Unable to complete the operation. Cached mail is retained. Check connection and account settings."
}

private fun LiveMailState.searchingFor(action: LiveMailEvent?): Boolean =
    action !is LiveMailEvent.Source && (query.isNotBlank() || searchAllFolders)

private fun keepInitialOnFailure(action: LiveMailEvent?, operation: MailOperation?): Boolean =
    operation?.cancellationRequested == true || action is LiveMailEvent.Source

private fun LiveMailState.forCachedReload(): LiveMailState = copy(
    account = returnAccount ?: account,
    folder = returnFolder ?: folder,
    returnAccount = null,
    returnFolder = null,
    content = null,
    source = null,
)
