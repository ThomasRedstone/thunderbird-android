package net.thunderbird.app.ubuntutouch

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

internal data class MailMoveUiState(
    val overview: MoveOverview = MoveOverview(),
    val request: MailMessageKey? = null,
    val role: MailFolderRole? = null,
    val target: String? = null,
    val selected: MailMove? = null,
    val page: MailSourcePage? = null,
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val cancelling: Boolean = false,
    val confirmRetry: Boolean = false,
    val notice: String? = null,
) {
    override fun toString(): String = "MailMoveUiState(redacted)"
}

internal sealed interface MailMoveEvent {
    data class Open(val source: MailMessageKey? = null, val role: MailFolderRole? = null) : MailMoveEvent
    data class Target(val folder: String) : MailMoveEvent {
        override fun toString(): String = "MailMoveEvent.Target(redacted)"
    }
    data class Select(val move: MailMove) : MailMoveEvent
    data class Source(val offset: Long = 0) : MailMoveEvent
    data object Verify : MailMoveEvent
    data object SyncDestination : MailMoveEvent
    data object ConfigureRole : MailMoveEvent
    data object ClearRole : MailMoveEvent
    data object Queue : MailMoveEvent
    data object Submit : MailMoveEvent
    data object Reconcile : MailMoveEvent
    data object ReviewRetry : MailMoveEvent
    data object ConfirmRetry : MailMoveEvent
    data object Keep : MailMoveEvent
    data object CancelQueued : MailMoveEvent
    data object Cancel : MailMoveEvent
    data object Back : MailMoveEvent
}

/** Single app owner; all writes are journaled and no init/background handler replays a move. */
internal class MailMoveViewModel(
    private val operations: MailMoveOperations,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(MailMoveUiState())
    val state = mutableState.asStateFlow()
    private var active: MailOperation? = null

    fun event(event: MailMoveEvent) {
        val current = state.value
        if (event == MailMoveEvent.Cancel) {
            background()
            return
        }
        if (current.busy) return
        when (event) {
            is MailMoveEvent.Target -> {
                require(current.overview.destinations.any { it.folder.id == event.folder })
                mutableState.value = current.copy(target = event.folder, confirmRetry = false)
            }

            MailMoveEvent.ReviewRetry -> if (current.selected?.state == MailMoveState.UNKNOWN) {
                mutableState.value = current.copy(confirmRetry = true)
            }

            MailMoveEvent.Keep -> mutableState.value = current.copy(confirmRetry = false)

            MailMoveEvent.Back -> mutableState.value = current.copy(
                page = null,
                selected = current.page?.let { current.selected },
                confirmRetry = false,
            )

            else -> perform(event, current)
        }
    }

    private fun perform(event: MailMoveEvent, initial: MailMoveUiState) {
        val network = event in NETWORK_EVENTS
        val operation = if (network) MailOperation() else null
        active = operation
        mutableState.value = initial.copy(busy = true, canCancel = network, confirmRetry = false, notice = null)
        scope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) { execute(event, initial, operation) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val recovered = withContext(Dispatchers.IO) { runCatching { refresh(initial) }.getOrDefault(initial) }
                mutableState.value =
                    recovered.copy(
                        busy = false,
                        canCancel = false,
                        cancelling = false,
                        confirmRetry = false,
                        notice = "Unable to complete move operation. Review the saved state before retrying.",
                    )
            } finally {
                active = null
            }
        }
    }

    private fun execute(event: MailMoveEvent, initial: MailMoveUiState, operation: MailOperation?): MailMoveUiState {
        val prepared = initial.copy(page = null, confirmRetry = false, notice = null)
        val next = when (event) {
            is MailMoveEvent.Open -> MailMoveUiState(request = event.source, role = event.role)

            is MailMoveEvent.Select -> {
                require(initial.overview.history.any { it.move == event.move })
                prepared.copy(selected = event.move)
            }

            is MailMoveEvent.Source -> return prepared.copy(
                page = operations.retained(checkNotNull(initial.selected), event.offset),
                busy = false,
                canCancel = false,
            )

            MailMoveEvent.ConfigureRole, MailMoveEvent.ClearRole -> {
                operations.configureRole(
                    checkNotNull(initial.request),
                    checkNotNull(initial.role),
                    if (event == MailMoveEvent.ClearRole) null else checkNotNull(initial.target),
                )
                prepared
            }

            MailMoveEvent.Queue -> {
                check(initial.overview.source?.verified == true)
                check(initial.overview.destinations.any { it.folder.id == initial.target && it.ready })
                prepared.copy(
                    selected = operations.enqueue(
                        checkNotNull(initial.request),
                        checkNotNull(initial.target),
                        initial.role,
                    ),
                )
            }

            MailMoveEvent.CancelQueued -> {
                operations.cancelQueued(checkNotNull(initial.selected))
                prepared.copy(selected = null)
            }

            else -> networkAction(event, initial, checkNotNull(operation))
        }
        return refresh(next)
    }

    private fun networkAction(
        event: MailMoveEvent,
        initial: MailMoveUiState,
        operation: MailOperation,
    ): MailMoveUiState {
        var prepared = initial.copy(page = null, confirmRetry = false, notice = null)
        when (event) {
            MailMoveEvent.Verify -> operations.verify(checkNotNull(initial.request), operation)

            MailMoveEvent.SyncDestination -> operations.syncDestination(
                checkNotNull(initial.request),
                checkNotNull(initial.target),
                operation,
            )

            MailMoveEvent.Submit, MailMoveEvent.ConfirmRetry -> {
                if (event == MailMoveEvent.ConfirmRetry) check(initial.confirmRetry)
                prepared = prepared.copy(
                    selected = operations.submit(
                        checkNotNull(initial.selected),
                        operation,
                        event == MailMoveEvent.ConfirmRetry,
                    ),
                )
            }

            MailMoveEvent.Reconcile -> {
                val synced = operations.reconcile(checkNotNull(initial.selected), operation)
                prepared = prepared.copy(
                    notice = if (synced) {
                        "Folder sync completed. Unknown moves still require review."
                    } else {
                        "Some folders could not sync; any available retained copy is kept."
                    },
                )
            }

            else -> Unit
        }
        return prepared
    }

    private fun refresh(initial: MailMoveUiState): MailMoveUiState {
        val overview = operations.overview(initial.request)
        val selected = initial.selected?.let { old ->
            overview.history.find {
                it.move.id == old.id &&
                    it.move.account == old.account
            }?.move
        }
        return initial.copy(
            overview = overview,
            target = initial.target ?: initial.role?.let { overview.roles[it] },
            selected = selected,
            busy = false,
            canCancel = false,
            cancelling = false,
            confirmRetry = false,
        )
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
        active?.cancelIn(scope)
        scope.cancel()
    }
}

private val NETWORK_EVENTS = setOf(
    MailMoveEvent.Verify,
    MailMoveEvent.SyncDestination,
    MailMoveEvent.Submit,
    MailMoveEvent.ConfirmRetry,
    MailMoveEvent.Reconcile,
)
