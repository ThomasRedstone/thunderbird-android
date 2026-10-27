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

internal data class SignatureSettingsState(
    val accounts: List<UtMailAccount>,
    val account: AccountId? = null,
    val signature: MailSignature = MailSignature(),
    val input: TextFieldValue = TextFieldValue(),
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val confirmDiscard: Boolean = false,
    val notice: String? = null,
) {
    override fun toString(): String = "SignatureSettingsState(redacted)"
}

internal sealed interface SignatureSettingsEvent {
    data class Account(val id: AccountId) : SignatureSettingsEvent
    data class Text(val value: TextFieldValue) : SignatureSettingsEvent {
        override fun toString(): String = "SignatureSettingsEvent.Text(redacted)"
    }
    data class Keyboard(val edit: MaliitTextEdit) : SignatureSettingsEvent {
        override fun toString(): String = "SignatureSettingsEvent.Keyboard(redacted)"
    }
    data object Enabled : SignatureSettingsEvent
    data object Replies : SignatureSettingsEvent
    data object Forwards : SignatureSettingsEvent
    data object Save : SignatureSettingsEvent
    data object Back : SignatureSettingsEvent
    data object ReviewDiscard : SignatureSettingsEvent
    data object ConfirmDiscard : SignatureSettingsEvent
    data object Keep : SignatureSettingsEvent
}

internal class SignatureSettingsViewModel(
    private val accounts: UtAccountManager,
    private val store: MailSignatureStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(SignatureSettingsState(accounts.findAll()))
    val state = mutableState.asStateFlow()
    private var snapshot: UtMailAccount? = null

    fun event(event: SignatureSettingsEvent) {
        val current = state.value
        if (current.busy) return
        when (event) {
            is SignatureSettingsEvent.Account -> if (!current.dirty) load(event.id)

            is SignatureSettingsEvent.Text -> edit(event.value)

            is SignatureSettingsEvent.Keyboard -> edit(
                applyMaliitEdit(
                    current.input,
                    if (event.edit ==
                        MaliitTextEdit.Enter
                    ) {
                        MaliitTextEdit.Commit("\n")
                    } else {
                        event.edit
                    },
                ),
            )

            SignatureSettingsEvent.Enabled -> change(current.signature.copy(enabled = !current.signature.enabled))

            SignatureSettingsEvent.Replies -> change(current.signature.copy(replies = !current.signature.replies))

            SignatureSettingsEvent.Forwards -> change(current.signature.copy(forwards = !current.signature.forwards))

            SignatureSettingsEvent.Save -> scope.launch { flushForClose() }

            else -> navigate(event, current)
        }
    }

    private fun navigate(event: SignatureSettingsEvent, current: SignatureSettingsState) {
        when (event) {
            SignatureSettingsEvent.Back -> if (current.dirty) {
                mutableState.value = current.copy(notice = "Save or explicitly discard these signature edits first.")
            } else {
                snapshot = null
                mutableState.value = SignatureSettingsState(accounts.findAll())
            }

            SignatureSettingsEvent.ReviewDiscard -> mutableState.value = current.copy(confirmDiscard = current.dirty)

            SignatureSettingsEvent.Keep -> mutableState.value = current.copy(confirmDiscard = false)

            SignatureSettingsEvent.ConfirmDiscard -> if (current.confirmDiscard) {
                snapshot = null
                mutableState.value = SignatureSettingsState(accounts.findAll())
            }

            else -> Unit
        }
    }

    private fun edit(value: TextFieldValue) {
        if (state.value.account == null) return
        try {
            val signature = state.value.signature.copy(text = value.text)
            mutableState.value = state.value.copy(signature = signature, input = value, dirty = true, notice = null)
        } catch (_: IllegalArgumentException) {
            mutableState.value =
                state.value.copy(
                    notice = "Signature must be at most 16384 characters and contain no unsupported controls.",
                )
        }
    }

    private fun change(signature: MailSignature) {
        if (state.value.account !=
            null
        ) {
            mutableState.value = state.value.copy(signature = signature, dirty = true, notice = null)
        }
    }

    private fun load(id: AccountId) {
        mutableState.value = state.value.copy(busy = true)
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val account = checkNotNull(accounts.findById(id))
                    account to store.read(id)
                }
                snapshot = loaded.first
                mutableState.value = SignatureSettingsState(
                    accounts.findAll(),
                    id,
                    loaded.second,
                    TextFieldValue(loaded.second.text),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(busy = false, notice = "Unable to open signature settings.")
            }
        }
    }

    suspend fun flushForClose(): Boolean {
        state.first { !it.busy }
        val current = state.value
        if (!current.dirty) return true
        mutableState.value = current.copy(busy = true)
        return try {
            withContext(Dispatchers.IO) {
                synchronized(accounts) {
                    check(accounts.findById(checkNotNull(current.account)) === snapshot)
                    store.save(checkNotNull(current.account), current.signature)
                }
            }
            mutableState.value =
                current.copy(dirty = false, busy = false, confirmDiscard = false, notice = "Signature saved.")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value =
                current.copy(
                    busy = false,
                    notice = "Unable to save signature. Edits are retained; retry or discard explicitly.",
                )
            false
        }
    }

    fun flushOnBackground() {
        scope.launch { flushForClose() }
    }
    override fun close() {
        scope.cancel()
    }
}
