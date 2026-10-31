package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId

internal data class MessageSaveState(
    val busy: Boolean = false,
    val pending: ExportReconcileResult? = null,
    val destinations: List<ExportDestination> = emptyList(),
    val notice: String? = null,
    val confirmDiscard: Boolean = false,
    val attachments: List<CachedAttachment> = emptyList(),
) {
    override fun toString(): String = "MessageSaveState(redacted)"
}

internal sealed interface MessageSaveEvent {
    data class Prepare(
        val account: AccountId,
        val folder: String,
        val uid: String,
        val attachment: AttachmentReference? = null,
    ) : MessageSaveEvent {
        override fun toString(): String = "Prepare(redacted)"
    }
    data class Attachments(val key: MailMessageKey) : MessageSaveEvent {
        override fun toString(): String = "Attachments(redacted)"
    }
    data class PrepareAttachment(
        val key: MailMessageKey,
        val ordinal: Int,
        val expectedName: String,
        val expectedMimeType: String,
    ) : MessageSaveEvent {
        override fun toString(): String = "PrepareAttachment(redacted)"
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
    changes: Flow<ContentHubChange> = emptyFlow(),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(MessageSaveState(busy = true))
    val state = mutableState.asStateFlow()

    init {
        scope.launch { perform { recover() } }
        scope.launch {
            changes.collect { change ->
                state.first { !it.busy }
                if (state.value.pending?.record?.transfer == change.reference) event(MessageSaveEvent.Refresh)
            }
        }
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
            is MessageSaveEvent.Attachments -> {
                check(record == null)
                MessageSaveState(attachments = controller.attachments(event.key))
            }

            is MessageSaveEvent.PrepareAttachment -> {
                check(record == null)
                val attachment = controller.attachments(event.key).getOrNull(event.ordinal)
                    ?: error("Attachment absent")
                check(attachment.displayName == event.expectedName && attachment.mimeType == event.expectedMimeType) {
                    "Cached attachment changed; reopen the message"
                }
                prepareAttachment(attachment.reference)
            }

            is MessageSaveEvent.Prepare -> {
                check(record == null)
                prepare(event.account, event.folder, event.uid, event.attachment)
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

    private fun prepareAttachment(reference: AttachmentReference): MessageSaveState =
        prepare(reference.account, reference.folder, reference.uid, reference)

    private fun prepare(
        accountId: AccountId,
        folder: String,
        uid: String,
        attachment: AttachmentReference?,
    ): MessageSaveState {
        val account = checkNotNull(accounts.findById(accountId))
        val ready = controller.prepare(
            account.id,
            folder,
            uid,
            authorized = { accounts.findById(account.id) === account },
            handoff = { send -> accounts.withCurrentAccount(account, send) },
            attachment = attachment,
        )
        return MessageSaveState(pending = ExportReconcileResult(ready), destinations = controller.destinations())
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

    suspend fun flushForClose(): Boolean {
        state.first { !it.busy }
        return true
    }

    override fun close() {
        scope.cancel()
    }
}
