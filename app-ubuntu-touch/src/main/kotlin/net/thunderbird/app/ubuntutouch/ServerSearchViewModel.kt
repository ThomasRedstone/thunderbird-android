package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId

internal data class ServerSearchState(
    val account: AccountId? = null,
    val accountName: String = "",
    val folder: MailFolder? = null,
    val input: TextFieldValue = TextFieldValue(),
    val fullText: Boolean = false,
    val results: ServerMailResults? = null,
    val content: MailContent? = null,
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val notice: String? = null,
) {
    override fun toString(): String = "ServerSearchState(redacted)"
}

internal sealed interface ServerSearchEvent {
    data class Open(val account: AccountId, val folder: MailFolder) : ServerSearchEvent
    data class Text(val value: TextFieldValue) : ServerSearchEvent {
        override fun toString(): String = "ServerSearchEvent.Text(redacted)"
    }
    data class Keyboard(val edit: MaliitTextEdit) : ServerSearchEvent {
        override fun toString(): String = "ServerSearchEvent.Keyboard(redacted)"
    }
    data class Read(val uid: String) : ServerSearchEvent
    data object FullText : ServerSearchEvent
    data object Search : ServerSearchEvent
    data object Back : ServerSearchEvent
    data object Cancel : ServerSearchEvent
}

internal class ServerSearchViewModel(
    private val operations: ServerSearchOperations,
    private val accounts: UtAccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ServerSearchState())
    val state = mutableState.asStateFlow()
    private var owner: UtMailAccount? = null
    private var active: MailOperation? = null
    private var closed = false

    init {
        scope.launch {
            accounts.observeAll().collect { current ->
                val expected = owner
                if (expected != null && current.none { it == expected }) {
                    active?.cancelIn(scope)
                    owner = null
                    mutableState.value = ServerSearchState(
                        busy = state.value.busy,
                        cancelling = state.value.busy,
                        notice = "Account changed or removed. Return to mailboxes and search again.",
                    )
                }
            }
        }
    }

    fun event(event: ServerSearchEvent) {
        if (closed) return
        if (event == ServerSearchEvent.Cancel) {
            background()
        } else if (!state.value.busy) {
            reduce(event, state.value)
        }
    }

    private fun reduce(event: ServerSearchEvent, current: ServerSearchState) {
        when (event) {
            is ServerSearchEvent.Open -> open(event)

            is ServerSearchEvent.Text -> edit(event.value)

            is ServerSearchEvent.Keyboard -> if (event.edit == MaliitTextEdit.Enter) {
                search(current)
            } else {
                edit(applyMaliitEdit(current.input, event.edit))
            }

            ServerSearchEvent.FullText -> mutableState.value = current.copy(
                fullText = !current.fullText,
                results = null,
                content = null,
                notice = null,
            )

            ServerSearchEvent.Search -> search(current)

            is ServerSearchEvent.Read -> if (current.results?.messages?.any { it.id == event.uid } == true) {
                execute(current, event.uid)
            }

            ServerSearchEvent.Back -> mutableState.value = current.copy(content = null, notice = null)

            ServerSearchEvent.Cancel -> Unit
        }
    }

    private fun open(request: ServerSearchEvent.Open) {
        val account = accounts.findById(request.account)
        if (account == null) {
            owner = null
            mutableState.value = ServerSearchState(notice = "Account unavailable. Return to mailboxes.")
        } else {
            val current = state.value
            if (owner == account && current.folder == request.folder) {
                mutableState.value = current.copy(content = null)
            } else {
                mutableState.value = ServerSearchState(account.id, account.displayName, request.folder)
            }
            owner = account
        }
    }

    private fun edit(value: TextFieldValue) {
        mutableState.value = state.value.copy(input = value, results = null, content = null, notice = null)
    }

    private fun search(current: ServerSearchState) {
        val query = runCatching { ServerMailQuery(current.input.text, current.fullText) }.getOrNull()
        if (query == null) {
            mutableState.value = current.copy(
                notice = "Enter 1–512 characters without line breaks or control characters.",
            )
        } else {
            execute(current, query = query)
        }
    }

    private fun execute(initial: ServerSearchState, uid: String? = null, query: ServerMailQuery? = null) {
        val snapshot = owner ?: return
        val folder = initial.folder ?: return
        val operation = MailOperation()
        active = operation
        mutableState.value = initial.copy(busy = true, cancelling = false, notice = null)
        scope.launch {
            try {
                check(accounts.findById(snapshot.id) == snapshot)
                val loaded = withContext(Dispatchers.IO) {
                    operation.ensureActive()
                    if (uid == null) {
                        val results = operations.search(snapshot.id, folder.id, checkNotNull(query), operation)
                        check(results.account == snapshot.id && results.folder == folder.id)
                        check(results.binding == mailCacheBinding(snapshot))
                        initial.copy(results = results, content = null)
                    } else {
                        val content = operations.open(checkNotNull(initial.results), uid, operation)
                        check(content.summary.id == uid)
                        initial.copy(content = content)
                    }
                }
                operation.ensureActive()
                check(owner == snapshot && accounts.findById(snapshot.id) == snapshot)
                mutableState.value = loaded.copy(busy = false, cancelling = false, notice = null)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.value = if (owner != snapshot) {
                    ServerSearchState(notice = "Account changed or removed. Return to mailboxes and search again.")
                } else {
                    initial.copy(
                        busy = false,
                        cancelling = false,
                        notice = if (operation.cancellationRequested) {
                            "Cancelled. Cached mail is retained."
                        } else {
                            "Unable to complete server search. Check the account and connection; refresh the folder if it changed."
                        },
                    )
                }
            } finally {
                if (active === operation) active = null
            }
        }
    }

    fun background() {
        active?.cancelIn(scope)
        if (active != null) mutableState.value = state.value.copy(cancelling = true)
    }
    suspend fun flushForClose(): Boolean {
        background()
        state.first { !it.busy }
        return true
    }
    override fun close() {
        closed = true
        active?.cancelIn(scope)
        scope.cancel()
    }
}
