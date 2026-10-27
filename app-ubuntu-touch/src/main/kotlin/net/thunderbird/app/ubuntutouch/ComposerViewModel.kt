package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions") // Composer events, serialized IO and lifecycle orchestration.
internal class ComposerViewModel(
    private val manager: UtAccountManager,
    private val store: OutgoingMailStore,
    private val sender: OutgoingMailSender,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val filing: SentCopyFiler? = null,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ComposerState(manager.findAll()))
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var autosave: Job? = null
    private var activeOperation: MailOperation? = null
    private var localWork: Job? = null
    private var localWorkFailed = false

    fun event(event: ComposerEvent) {
        val current = state.value
        if (event == ComposerEvent.Cancel) {
            cancel(current)
            return
        }
        if (current.busy || handleFiling(event, current)) return
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

            ComposerEvent.Retry -> reviewRetry(current)

            ComposerEvent.ConfirmRetry -> if (current.confirmRetry) send(retry = true, acknowledge = true)

            ComposerEvent.CancelRetry -> mutableState.value = current.copy(confirmRetry = false)

            else -> navigate(event, current)
        }
    }

    private fun reviewRetry(current: ComposerState) {
        if (current.selected?.state == OutgoingState.UNCERTAIN || current.selected?.hasUncertainAttempt == true) {
            mutableState.value = current.copy(confirmRetry = true)
        } else {
            send(retry = true, acknowledge = false)
        }
    }

    private fun handleFiling(event: ComposerEvent, current: ComposerState): Boolean {
        when (event) {
            ComposerEvent.SentSettings ->
                mutableState.value =
                    current.copy(showSentSettings = !current.showSentSettings)

            is ComposerEvent.SentFolder -> if (!current.dirty) {
                operate {
                    checkNotNull(filing).configure(checkNotNull(it.account), event.folder)
                    reload(it, it.selected?.id)
                }
            }

            ComposerEvent.FileSent -> fileSent(acknowledge = false)

            ComposerEvent.ReviewCopy -> mutableState.value = current.copy(confirmCopy = true)

            ComposerEvent.CancelCopy -> mutableState.value = current.copy(confirmCopy = false)

            ComposerEvent.ConfirmCopy -> if (current.confirmCopy) fileSent(acknowledge = true)

            else -> return false
        }
        return true
    }

    private fun fileSent(acknowledge: Boolean) {
        val operation = MailOperation()
        operate(operation) {
            val selected = checkNotNull(it.selected)
            check(selected.state == OutgoingState.SENT)
            checkNotNull(filing).file(checkNotNull(it.account), selected.id, operation, acknowledge)
            reload(it, selected.id)
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
            is ComposerEvent.Resume -> if (!current.dirty) {
                operate {
                    check(manager.findById(event.request.account) != null)
                    reload(ComposerState(manager.findAll(), account = event.request.account), event.request.id)
                        .copy(notice = event.request.notice)
                }
            }

            is ComposerEvent.Account -> if (!current.dirty) {
                operate {
                    check(manager.findById(event.id) != null)
                    reload(ComposerState(manager.findAll(), account = event.id), null)
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
            sender.send(account, id, operation, acknowledgeDuplicateRisk = acknowledge) {
                mutableState.update { latest ->
                    val selected = latest.selected
                    if (selected?.id ==
                        id
                    ) {
                        latest.copy(selected = selected.copy(state = OutgoingState.SENT))
                    } else {
                        latest
                    }
                }
            }
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
            cc = current.fields.getValue(ComposeField.CC).text,
            bcc = current.fields.getValue(ComposeField.BCC).text,
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
                    ComposeField.CC to TextFieldValue(it.cc),
                    ComposeField.BCC to TextFieldValue(it.bcc),
                    ComposeField.SUBJECT to TextFieldValue(it.subject),
                    ComposeField.BODY to TextFieldValue(it.body),
                )
            }.orEmpty(),
            dirty = false,
            busy = false,
            notice = null,
            confirmRetry = false,
            confirmCopy = false,
            sentFolders = filing?.folders(account).orEmpty(),
            sentTarget = filing?.target(account),
            sentCopy = selected?.let { filing?.status(account, it.id) },
        )
    }

    private fun operate(operation: MailOperation? = null, block: (ComposerState) -> ComposerState) {
        autosave?.cancel()
        val snapshot = state.value
        activeOperation = operation
        mutableState.value =
            snapshot.copy(busy = true, canCancel = operation != null, cancelling = false, notice = null)
        localWorkFailed = false
        val work = scope.launch {
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
                if (operation == null) localWorkFailed = true
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
        if (operation == null) localWork = work
    }

    suspend fun flushForClose(): Boolean {
        while (true) {
            val pending = localWork
            pending?.join()
            if (pending === localWork) break
        }
        autosave?.cancelAndJoin()
        val snapshot = state.value
        if (!snapshot.dirty || snapshot.selected?.state != OutgoingState.DRAFT) {
            val saved = !localWorkFailed
            localWorkFailed = false
            return saved
        }
        return try {
            val saved = withContext(Dispatchers.IO) {
                writes.withLock {
                    val row = store.get(checkNotNull(snapshot.account), checkNotNull(snapshot.selected).id)
                    if (row.state == OutgoingState.DRAFT) saveDraft(snapshot) else snapshot.copy(dirty = false)
                }
            }
            val latest = state.value
            val unchanged = latest.selected?.id == snapshot.selected?.id && latest.fields == snapshot.fields
            if (unchanged) {
                localWorkFailed = false
                mutableState.value =
                    latest.copy(selected = saved.selected, items = saved.items, dirty = false)
            }
            unchanged
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            mutableState.value = state.value.copy(notice = "Draft could not be saved. Keep this screen open and retry.")
            false
        }
    }

    fun flushOnBackground() {
        if (state.value.dirty && !state.value.busy) event(ComposerEvent.Save)
    }

    override fun close() {
        activeOperation?.cancelIn(scope)
        scope.cancel()
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 400L
    }
}
