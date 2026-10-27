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
    val composeRequest: ComposeRequest? = null,
    val pendingActions: Int = 0,
    val confirmDiscard: Boolean = false,
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val cancelling: Boolean = false,
    val notice: String? = null,
) {
    override fun toString(): String = "LiveMailState(redacted)"
}

internal sealed interface LiveMailEvent {
    data class OpenAccount(val id: AccountId) : LiveMailEvent
    data class OpenFolder(val folder: MailFolder) : LiveMailEvent
    data class OpenMessage(val uid: String) : LiveMailEvent
    data class Respond(val mode: ResponseMode) : LiveMailEvent
    data class ChangeFlag(val flag: Flag, val enabled: Boolean) : LiveMailEvent
    data object DiscardPending : LiveMailEvent
    data object ConfirmDiscard : LiveMailEvent
    data object CancelDiscard : LiveMailEvent
    data object Refresh : LiveMailEvent
    data object Download : LiveMailEvent
    data object Cancel : LiveMailEvent
    data object Back : LiveMailEvent
}

internal class LiveMailViewModel(
    private val manager: UtAccountManager,
    private val repository: LiveMailRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(LiveMailState(manager.findAll()))
    val state = mutableState.asStateFlow()
    private var activeOperation: MailOperation? = null
    private var localWrite: Job? = null
    private var localWriteFailed = false

    fun event(event: LiveMailEvent) {
        val current = state.value
        if (event == LiveMailEvent.Cancel) {
            cancel(current)
            return
        }
        if (current.busy || handleActions(event, current)) return
        when (event) {
            is LiveMailEvent.OpenAccount -> {
                require(manager.findById(event.id) != null)
                load(LiveMailState(manager.findAll(), account = event.id))
            }

            is LiveMailEvent.OpenFolder -> {
                require(event.folder in current.folders)
                load(current.copy(folder = event.folder, messages = emptyList(), content = null))
            }

            is LiveMailEvent.OpenMessage -> {
                require(current.messages.any { it.id == event.uid })
                load(current, uid = event.uid)
            }

            LiveMailEvent.Refresh -> load(current, network = true)

            LiveMailEvent.Download -> load(current, uid = current.content?.summary?.id, download = true)

            LiveMailEvent.Back -> mutableState.value = when {
                current.content != null -> current.copy(content = null, notice = null)
                current.folder != null -> current.copy(folder = null, messages = emptyList(), notice = null)
                else -> LiveMailState(manager.findAll())
            }

            else -> Unit
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
            is LiveMailEvent.Respond -> current.content?.let { load(current, uid = it.summary.id, action = event) }

            is LiveMailEvent.ChangeFlag -> current.content?.let { load(current, uid = it.summary.id, action = event) }

            LiveMailEvent.DiscardPending -> mutableState.value = current.copy(confirmDiscard = true)

            LiveMailEvent.CancelDiscard -> mutableState.value = current.copy(confirmDiscard = false)

            LiveMailEvent.ConfirmDiscard -> if (current.confirmDiscard) {
                load(current.copy(confirmDiscard = false), uid = current.content?.summary?.id, action = event)
            }

            else -> return false
        }
        return true
    }

    private fun load(
        initial: LiveMailState,
        uid: String? = null,
        network: Boolean = false,
        download: Boolean = false,
        action: LiveMailEvent? = null,
    ) {
        if (initial.account == null) return
        val operation = if (network || download) MailOperation() else null
        activeOperation = operation
        mutableState.value = initial.copy(busy = true, canCancel = operation != null, cancelling = false, notice = null)
        if (action != null) localWriteFailed = false
        val work = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    perform(initial, uid, network, download, action, operation)
                }
                mutableState.value = loaded.copy(
                    canCancel = false,
                    cancelling = false,
                    notice = if (operation?.cancellationRequested ==
                        true
                    ) {
                        "Operation stopped. Cached mail is retained."
                    } else {
                        null
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (action != null) localWriteFailed = true
                val retained =
                    withContext(Dispatchers.IO) { runCatching { readCache(initial, uid) }.getOrDefault(initial) }
                mutableState.value = retained.copy(
                    busy = false,
                    canCancel = false,
                    cancelling = false,
                    notice = if (operation?.cancellationRequested == true) {
                        "Cancelled. Cached mail is retained."
                    } else {
                        "Unable to complete the operation. Cached mail is retained. Check connection and account settings."
                    },
                )
            } finally {
                if (activeOperation === operation) activeOperation = null
            }
        }
        if (action != null) localWrite = work
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
        if (action is LiveMailEvent.ChangeFlag && uid != null) {
            repository.changeFlag(
                account,
                checkNotNull(initial.folder).id,
                uid,
                action.flag,
                action.enabled,
            )
        }
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
        return readCache(prepared, uid)
    }

    private fun readCache(initial: LiveMailState, uid: String?): LiveMailState {
        val account = checkNotNull(initial.account)
        val folders = repository.folders(account)
        val folder = initial.folder?.let { old -> folders.find { it.id == old.id } }
        return initial.copy(
            folders = folders,
            pendingActions = repository.pendingFlags(account),
            folder = folder,
            messages = folder?.let { repository.messages(account, it.id) }.orEmpty(),
            content = if (uid != null && folder != null) repository.content(account, folder.id, uid) else null,
            busy = false,
        )
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
