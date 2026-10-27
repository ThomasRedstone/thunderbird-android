package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import com.fsck.k9.mail.ConnectionSecurity
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

internal class AccountSetupViewModel(
    private val manager: UtAccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(AccountSetupState(manager.findAll()))
    val state = mutableState.asStateFlow()

    fun event(event: AccountSetupEvent) {
        val current = state.value
        if (current.busy) return
        when (event) {
            AccountSetupEvent.New -> mutableState.value = AccountSetupState(manager.findAll(), editing = true)

            is AccountSetupEvent.Edit -> edit(event.id)

            is AccountSetupEvent.KeyboardEdit -> mutableState.value = current.copy(
                fields =
                current.fields + (event.field to applyMaliitEdit(current.fields.getValue(event.field), event.edit)),
            )

            is AccountSetupEvent.Change ->
                mutableState.value =
                    current.copy(fields = current.fields + (event.field to event.value))

            is AccountSetupEvent.StartTls -> mutableState.value = if (event.incoming) {
                current.copy(imapStartTls = event.enabled)
            } else {
                current.copy(smtpStartTls = event.enabled)
            }

            AccountSetupEvent.Cancel -> mutableState.value = AccountSetupState(manager.findAll())

            AccountSetupEvent.Save -> save(current)

            is AccountSetupEvent.RequestRemoval -> mutableState.value = current.copy(pendingRemoval = event.id)

            AccountSetupEvent.CancelRemoval -> mutableState.value = current.copy(pendingRemoval = null)

            AccountSetupEvent.ConfirmRemoval -> remove(current)
        }
    }

    private fun edit(id: AccountId) {
        val account = checkNotNull(manager.findById(id))
        val fields = mapOf(
            AccountField.NAME to account.displayName, AccountField.ADDRESS to account.address,
            AccountField.IMAP_HOST to account.incoming.host, AccountField.IMAP_PORT to account.incoming.port.toString(),
            AccountField.IMAP_USER to account.incoming.username, AccountField.IMAP_PASSWORD to "",
            AccountField.SMTP_HOST to account.outgoing.host, AccountField.SMTP_PORT to account.outgoing.port.toString(),
            AccountField.SMTP_USER to account.outgoing.username, AccountField.SMTP_PASSWORD to "",
        ).mapValues { TextFieldValue(it.value) }
        mutableState.value = AccountSetupState(
            manager.findAll(),
            editing = true,
            editingId = id,
            fields = fields,
            imapStartTls = account.incoming.connectionSecurity == ConnectionSecurity.STARTTLS_REQUIRED,
            smtpStartTls = account.outgoing.connectionSecurity == ConnectionSecurity.STARTTLS_REQUIRED,
        )
    }

    private fun save(current: AccountSetupState) = perform(current) {
        val existing = current.editingId?.let { checkNotNull(manager.findById(it)) }
        val account = current.account(existing)
        val incoming = current.text(AccountField.IMAP_PASSWORD)
        val outgoing = current.text(AccountField.SMTP_PASSWORD)
        val replace = incoming.isNotEmpty() || outgoing.isNotEmpty()
        var cleaned = true
        when {
            current.editingId == null -> manager.createWithPasswords(account, AccountPasswords(incoming, outgoing))
            replace -> cleaned = manager.replaceAccountAndPasswords(account, AccountPasswords(incoming, outgoing))
            else -> manager.updateSync(account.id) { account }
        }
        if (current.editingId == null || !replace) cleaned = manager.cleanupUnusedCredentials()
        if (!cleaned) {
            return@perform "Account saved. Unlock the keyring and save again to remove obsolete passwords."
        }
        "Account saved. Mail receiving and sending are not connected in this preview."
    }

    private fun remove(current: AccountSetupState) {
        val id = current.pendingRemoval ?: return
        perform(current) {
            manager.delete(id)
            "Account and its local cached mail removed. Server mail was not deleted."
        }
    }

    private fun perform(current: AccountSetupState, operation: () -> String) {
        mutableState.value = current.copy(busy = true, message = null)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess { mutableState.value = AccountSetupState(manager.findAll(), message = it) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    val fields = current.fields.mapValues { (field, value) ->
                        if (field.secret) TextFieldValue() else value
                    }
                    mutableState.value = current.copy(busy = false, fields = fields, message = errorMessage(failure))
                }
        }
    }

    override fun close() {
        scope.cancel()
        mutableState.value = AccountSetupState(emptyList())
    }
}

private fun errorMessage(failure: Throwable): String = when (failure) {
    is SecretStoreException -> when (failure.reason) {
        SecretFailure.LOCKED -> "Unlock the system keyring, then try again. Passwords were not saved to a file."
        SecretFailure.PROMPT_REQUIRED -> "The system keyring requires a prompt that this preview cannot show yet."
        else -> "Protected credential storage is unavailable. No plaintext fallback was used."
    }

    is IllegalArgumentException ->
        "Check the address, server names, ports and usernames. " +
            "Enter both passwords for a new account or password change. TLS is required."

    else -> "Unable to complete changes. Check device storage and try again."
}
