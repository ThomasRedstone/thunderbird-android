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

internal data class MessageSaveState(
    val busy: Boolean = false,
    val pending: ExportReconcileResult? = null,
    val destinations: List<ExportDestination> = emptyList(),
    val notice: String? = null,
    val confirmDiscard: Boolean = false,
) {
    override fun toString(): String = "MessageSaveState(redacted)"
}

internal sealed interface MessageSaveEvent {
    data class Prepare(val account: AccountId, val folder: String, val uid: String) : MessageSaveEvent {
        override fun toString(): String = "Prepare(redacted)"
    }
    data class Select(val destination: ExportDestination) : MessageSaveEvent
    data object Refresh : MessageSaveEvent
    data object Cancel : MessageSaveEvent
    data object ReviewDiscard : MessageSaveEvent
    data object ConfirmDiscard : MessageSaveEvent
    data object Keep : MessageSaveEvent
}

/** Foreground owner. Restart recovery never repeats Charge; leaving this model retains durable transfer records. */
internal class MessageSaveViewModel(
    private val controller: ContentExportController,
    private val accounts: UtAccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(MessageSaveState(busy = true))
    val state = mutableState.asStateFlow()

    init {
        scope.launch { perform { recover() } }
    }

    fun event(event: MessageSaveEvent) {
        if (state.value.busy) return
        when (event) {
            MessageSaveEvent.ReviewDiscard -> mutableState.value = state.value.copy(confirmDiscard = true)

            MessageSaveEvent.Keep -> mutableState.value = state.value.copy(confirmDiscard = false)

            else -> {
                val initial = state.value
                mutableState.value = initial.copy(busy = true)
                scope.launch { perform { act(event, initial) } }
            }
        }
    }

    private fun act(event: MessageSaveEvent, initial: MessageSaveState): MessageSaveState {
        val record = initial.pending?.record
        return when (event) {
            is MessageSaveEvent.Prepare -> {
                check(record == null)
                val account = checkNotNull(accounts.findById(event.account))
                val ready = controller.prepare(
                    account.id,
                    event.folder,
                    event.uid,
                    authorized = { accounts.findById(account.id) === account },
                    handoff = { send -> accounts.exportHandoff(account, send) },
                )
                MessageSaveState(pending = ExportReconcileResult(ready), destinations = controller.destinations())
            }

            is MessageSaveEvent.Select -> {
                check(event.destination in initial.destinations)
                MessageSaveState(pending = controller.send(checkNotNull(record), event.destination))
            }

            MessageSaveEvent.Cancel -> MessageSaveState(pending = record?.let { controller.cancel(it.id) })

            MessageSaveEvent.ConfirmDiscard -> {
                check(initial.confirmDiscard)
                MessageSaveState(pending = record?.let { controller.discardLocalCopy(it) })
            }

            else -> refresh(record)
        }
    }

    private fun refresh(record: ExportLeaseRecord?): MessageSaveState {
        if (record == null) return recover()
        val current = controller.poll(record.id)
        val destinations = if (current.record?.phase ==
            ExportLeasePhase.READY
        ) {
            controller.destinations()
        } else {
            emptyList()
        }
        return MessageSaveState(pending = current, destinations = destinations)
    }

    private fun recover(): MessageSaveState {
        val recovered = controller.recover()
        check(recovered.size <= 1) { "Multiple pending exports require review" }
        return MessageSaveState(pending = recovered.singleOrNull())
    }

    private suspend fun perform(action: () -> MessageSaveState) {
        try {
            mutableState.value = withContext(Dispatchers.IO) { action() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val recovered = withContext(Dispatchers.IO) { runCatching { recover() }.getOrNull() }
            mutableState.value = (recovered ?: state.value).copy(
                busy = false,
                destinations = emptyList(),
                confirmDiscard = false,
                notice = "Unable to save. Cached mail and pending transfers are retained.",
            )
        }
    }

    override fun close() {
        scope.cancel()
    }
}
