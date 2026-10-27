package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ComposerViewModel(
    private val manager: UtAccountManager,
    private val store: OutgoingMailStore,
    private val sender: OutgoingMailSender,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ComposerState(manager.findAll()))
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var autosave: Job? = null
    private var activeOperation: MailOperation? = null

    fun event(event: ComposerEvent) {
        val current = state.value
        if (event == ComposerEvent.Cancel) {
            cancel(current)
            return
        }
        if (current.busy) return
        when (event) {
            is ComposerEvent.Change -> change(event.field, event.value)

            is ComposerEvent.Keyboard -> change(
                event.field,
                applyMaliitEdit(current.fields.getValue(event.field), event.edit),
            )

            ComposerEvent.Save -> operate { saveDraft(it) }

            ComposerEvent.Queue -> operate {
                val saved = saveDraft(it)
                store.queue(
                    checkNotNull(manager.findById(checkNotNull(saved.account))),
                    checkNotNull(saved.selected).id,
                )
                reload(saved, checkNotNull(saved.selected).id)
            }

            ComposerEvent.Send -> send(retry = false, acknowledge = false)

            ComposerEvent.Retry -> {
                if (current.selected?.state == OutgoingState.UNCERTAIN ||
                    current.selected?.hasUncertainAttempt == true
                ) {
                    mutableState.value = current.copy(confirmRetry = true)
                } else {
                    send(retry = true, acknowledge = false)
                }
            }

            ComposerEvent.ConfirmRetry -> if (current.confirmRetry) send(retry = true, acknowledge = true)

            ComposerEvent.CancelRetry -> mutableState.value = current.copy(confirmRetry = false)

            else -> navigate(event, current)
        }
    }

    private fun cancel(current: ComposerState) {
        if (current.busy && current.canCancel) {
            activeOperation?.cancelIn(scope)
            mutableState.value = current.copy(cancelling = true, notice = "Cancelling… Waiting for the saved outcome.")
        }
    }

    private fun navigate(event: ComposerEvent, current: ComposerState) {
        when (event) {
            is ComposerEvent.Account -> if (!current.dirty) {
                operate {
                    check(manager.findById(event.id) != null)
                    ComposerState(manager.findAll(), account = event.id, items = store.list(event.id))
                }
            }

            is ComposerEvent.Open -> if (!current.dirty) operate { reload(it, event.id) }

            ComposerEvent.New -> if (!current.dirty) {
                operate {
                    val draft = store.create(checkNotNull(it.account))
                    reload(it, draft.id)
                }
            }

            ComposerEvent.Back -> if (current.selected != null) {
                operate { reload(saveDraft(it), null) }
            } else if (!current.dirty) {
                mutableState.value = ComposerState(manager.findAll())
            }

            else -> Unit
        }
    }

    private fun change(field: ComposeField, value: TextFieldValue) {
        val current = state.value
        if (current.selected?.state != OutgoingState.DRAFT) return
        mutableState.value = current.copy(fields = current.fields + (field to value), dirty = true, notice = null)
        autosave?.cancel()
        autosave = scope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val snapshot = state.value
            try {
                val saved = withContext(Dispatchers.IO) { writes.withLock { saveDraft(snapshot) } }
                val latest = state.value
                if (latest.selected?.id == snapshot.selected?.id) {
                    mutableState.value = latest.copy(
                        selected = saved.selected,
                        items = saved.items,
                        dirty = latest.fields != snapshot.fields,
                        notice = "Draft saved",
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.value =
                    state.value.copy(notice = "Draft could not be saved. Keep this screen open and retry.")
            }
        }
    }

    private fun send(retry: Boolean, acknowledge: Boolean) {
        val operation = MailOperation()
        operate(operation) { current ->
            val account = checkNotNull(current.account)
            val id = checkNotNull(current.selected).id
            if (retry) store.retry(account, id, acknowledge)
            sender.send(account, id, operation, acknowledgeDuplicateRisk = acknowledge)
            reload(current, id)
        }
    }

    private fun saveDraft(current: ComposerState): ComposerState {
        val selected = current.selected
        if (selected?.state != OutgoingState.DRAFT) return current
        val account = checkNotNull(current.account)
        val saved = store.save(
            account,
            selected.id,
            current.fields.getValue(ComposeField.TO).text,
            current.fields.getValue(ComposeField.SUBJECT).text,
            current.fields.getValue(ComposeField.BODY).text,
        )
        return current.copy(selected = saved, items = store.list(account), dirty = false, notice = "Draft saved")
    }

    private fun reload(current: ComposerState, id: String?): ComposerState {
        val account = checkNotNull(current.account)
        val selected = id?.let { store.get(account, it) }
        return current.copy(
            items = store.list(account),
            selected = selected,
            fields = selected?.let {
                mapOf(
                    ComposeField.TO to TextFieldValue(it.recipients),
                    ComposeField.SUBJECT to TextFieldValue(it.subject),
                    ComposeField.BODY to TextFieldValue(it.body),
                )
            }.orEmpty(),
            dirty = false,
            busy = false,
            notice = null,
            confirmRetry = false,
        )
    }

    private fun operate(operation: MailOperation? = null, block: (ComposerState) -> ComposerState) {
        autosave?.cancel()
        val snapshot = state.value
        activeOperation = operation
        mutableState.value =
            snapshot.copy(busy = true, canCancel = operation != null, cancelling = false, notice = null)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { writes.withLock { block(snapshot) } }
                mutableState.value = result.copy(
                    busy = false,
                    canCancel = false,
                    cancelling = false,
                    notice = if (operation?.cancellationRequested == true) {
                        "Cancellation requested. Review the saved delivery state before another attempt."
                    } else {
                        result.notice
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.value = snapshot.copy(
                    busy = false,
                    canCancel = false,
                    cancelling = false,
                    notice = "Unable to complete the operation. Saved drafts and outbox records are retained.",
                )
            } finally {
                if (activeOperation === operation) activeOperation = null
            }
        }
    }

    override fun close() {
        activeOperation?.cancelIn(scope)
        scope.cancel()
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 400L
    }
}
