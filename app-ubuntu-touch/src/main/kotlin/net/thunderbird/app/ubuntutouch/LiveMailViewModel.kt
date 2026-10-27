package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId

internal data class LiveMailState(
    val accounts: List<UtMailAccount>,
    val account: AccountId? = null,
    val folders: List<MailFolder> = emptyList(),
    val folder: MailFolder? = null,
    val messages: List<MailSummary> = emptyList(),
    val content: MailContent? = null,
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

    fun event(event: LiveMailEvent) {
        val current = state.value
        if (event == LiveMailEvent.Cancel) {
            if (current.busy && current.canCancel) {
                activeOperation?.cancelIn(scope)
                mutableState.value =
                    current.copy(cancelling = true, notice = "Cancelling… Waiting for the operation to stop.")
            }
            return
        }
        if (current.busy) return
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

            LiveMailEvent.Cancel -> Unit

            LiveMailEvent.Refresh -> load(current, network = true)

            LiveMailEvent.Download -> load(current, uid = current.content?.summary?.id, download = true)

            LiveMailEvent.Back -> mutableState.value = when {
                current.content != null -> current.copy(content = null, notice = null)
                current.folder != null -> current.copy(folder = null, messages = emptyList(), notice = null)
                else -> LiveMailState(manager.findAll())
            }
        }
    }

    private fun load(initial: LiveMailState, uid: String? = null, network: Boolean = false, download: Boolean = false) {
        val account = initial.account ?: return
        val operation = if (network || download) MailOperation() else null
        activeOperation = operation
        mutableState.value = initial.copy(busy = true, canCancel = operation != null, cancelling = false, notice = null)
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    operation?.ensureActive()
                    if (network) repository.refresh(account, initial.folder?.id, checkNotNull(operation))
                    if (download && uid != null) {
                        repository.download(account, checkNotNull(initial.folder).id, uid, checkNotNull(operation))
                    }
                    operation?.ensureActive()
                    readCache(initial, uid)
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
                mutableState.value = initial.copy(
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
    }

    private fun readCache(initial: LiveMailState, uid: String?): LiveMailState {
        val account = checkNotNull(initial.account)
        val folders = repository.folders(account)
        val folder = initial.folder?.let { old -> folders.find { it.id == old.id } }
        return initial.copy(
            folders = folders,
            folder = folder,
            messages = folder?.let { repository.messages(account, it.id) }.orEmpty(),
            content = if (uid != null && folder != null) repository.content(account, folder.id, uid) else null,
            busy = false,
        )
    }

    override fun close() {
        activeOperation?.cancelIn(scope)
        scope.cancel()
    }
}
