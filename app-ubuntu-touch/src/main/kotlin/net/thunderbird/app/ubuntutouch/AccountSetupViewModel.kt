package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import com.fsck.k9.mail.ConnectionSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId

@Suppress("TooManyFunctions") // Password, OAuth and pending-credential cleanup share one account setup owner.
internal class AccountSetupViewModel(
    private val manager: UtAccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val authorization: OAuthAccountAuthorization? = null,
    private val providers: OAuthProviderRegistry = OAuthProviderRegistry(),
) : AutoCloseable {
    private var work: Job? = null
    private var closed = false
    private var editingSnapshot: UtMailAccount? = null
    private var activeAuthorization: OAuthAccountAuthorizationAttempt? = null
    private var cleanupPending = manager.hasPendingCredentialCleanup()
    private val mutableState = MutableStateFlow(initial())
    val state = mutableState.asStateFlow()

    fun event(event: AccountSetupEvent) {
        val current = state.value
        if (!acceptEvent(event, current)) return
        when (event) {
            AccountSetupEvent.New -> {
                editingSnapshot = null
                mutableState.value = initial().copy(editing = true)
            }

            is AccountSetupEvent.SelectProvider -> mutableState.value = current.selectProvider(event.id)

            is AccountSetupEvent.Edit -> edit(event.id)

            is AccountSetupEvent.KeyboardEdit -> mutableState.value = current.changeField(
                event.field,
                applyMaliitEdit(current.fields.getValue(event.field), event.edit),
            )

            is AccountSetupEvent.Change -> mutableState.value = current.changeField(event.field, event.value)

            is AccountSetupEvent.StartTls -> mutableState.value = if (event.incoming) {
                current.copy(imapStartTls = event.enabled)
            } else {
                current.copy(smtpStartTls = event.enabled)
            }

            AccountSetupEvent.Cancel -> {
                editingSnapshot = null
                mutableState.value = initial()
            }

            AccountSetupEvent.Save -> save(current)

            is AccountSetupEvent.RequestRemoval -> mutableState.value = current.copy(pendingRemoval = event.id)

            AccountSetupEvent.CancelRemoval -> mutableState.value = current.copy(pendingRemoval = null)

            AccountSetupEvent.ConfirmRemoval -> remove(current)

            AccountSetupEvent.RetryCredentialCleanup -> retryCleanup(current)
        }
    }

    private fun acceptEvent(event: AccountSetupEvent, current: AccountSetupState): Boolean {
        if (closed) return false
        if (current.busy && event == AccountSetupEvent.Cancel && current.signingIn) cancelAuthorization()
        return !current.busy
    }

    private fun initial(message: String? = null) = AccountSetupState(
        manager.findAll(),
        message = message,
        cleanupPending = cleanupPending,
        providers = if (authorization == null) emptyList() else providers.choices(),
    )

    private fun cancelAuthorization() {
        val cancelled = activeAuthorization?.cancel() == true
        mutableState.value = state.value.copy(
            cancelling = true,
            message = if (cancelled) "Cancelling sign-in…" else "Completing the protected account save…",
        )
    }

    private fun signIn(current: AccountSetupState) {
        val attempt = runCatching {
            val existing = current.editingId?.let { id -> checkNotNull(editingSnapshot).also { check(it.id == id) } }
            val candidate = providers.candidate(
                checkNotNull(current.providerId),
                current.text(AccountField.NAME),
                current.text(AccountField.ADDRESS).trim(),
                existing,
            )
            checkNotNull(authorization).prepare(candidate, existing)
        }.getOrElse {
            mutableState.value =
                current.copy(message = "Check the account name, address and available sign-in provider.")
            return
        }
        activeAuthorization = attempt
        mutableState.value = current.copy(busy = true, signingIn = true, message = "Continue sign-in in your browser.")
        work = scope.launch {
            val result = runCatching { attempt.authorize() }
            if (closed || !scope.isActive || activeAuthorization !== attempt) return@launch
            cleanupPending = withContext(Dispatchers.IO) {
                runCatching { manager.hasPendingCredentialCleanup() }.getOrDefault(true)
            }
            if (closed || !scope.isActive || activeAuthorization !== attempt) return@launch
            activeAuthorization = null
            result.onSuccess {
                mutableState.value = initial(
                    if (it.cleanupPending) {
                        "Account saved. Unlock the keyring, then use Retry credential cleanup in Accounts."
                    } else {
                        "Account signed in. Open Mailboxes to receive mail."
                    },
                )
            }.onFailure {
                mutableState.value = if (it is CancellationException) {
                    initial("Sign-in cancelled.")
                } else {
                    current.copy(
                        cleanupPending = cleanupPending,
                        message = oauthErrorMessage(it),
                        busy = false,
                        signingIn = false,
                        cancelling = false,
                    )
                }
            }
        }
    }

    private fun edit(id: AccountId) {
        val account = checkNotNull(manager.findById(id))
        editingSnapshot = account
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
            providers = initial().providers,
            providerId = account.oauthProviderId,
            fields = fields,
            imapStartTls = account.incoming.connectionSecurity == ConnectionSecurity.STARTTLS_REQUIRED,
            smtpStartTls = account.outgoing.connectionSecurity == ConnectionSecurity.STARTTLS_REQUIRED,
        )
    }

    private fun save(current: AccountSetupState) {
        if (current.providerId != null) {
            signIn(current)
            return
        }
        perform(current) {
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
            "Account saved. Open Mailboxes to receive mail or Write to compose a message."
        }
    }

    private fun retryCleanup(current: AccountSetupState) {
        if (current.editing || current.pendingRemoval != null) return
        perform(current) {
            if (manager.cleanupUnusedCredentials()) {
                "Obsolete credentials removed."
            } else {
                "Unlock the system keyring, then retry credential cleanup."
            }
        }
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
        work = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val message = try {
                        operation()
                    } finally {
                        cleanupPending = manager.hasPendingCredentialCleanup()
                    }
                    message to cleanupPending
                }
            }.onSuccess { (message, pending) ->
                if (!closed) {
                    cleanupPending = pending
                    mutableState.value = initial(message)
                }
            }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (closed) return@onFailure
                    val fields = current.fields.mapValues { (field, value) ->
                        if (field.secret) TextFieldValue() else value
                    }
                    mutableState.value = current.copy(
                        busy = false,
                        fields = fields,
                        cleanupPending = cleanupPending,
                        message = errorMessage(failure),
                    )
                }
        }
    }

    suspend fun flushForClose(): Boolean {
        activeAuthorization?.cancel()
        work?.join()
        return !state.value.busy
    }

    override fun close() {
        closed = true
        editingSnapshot = null
        activeAuthorization?.cancel()
        activeAuthorization = null
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

private fun oauthErrorMessage(failure: Throwable): String = when (failure) {
    is OAuthAuthorizationDeniedException -> "Sign-in was declined. No new authorization was saved."
    is OAuthBrowserException -> "Unable to open the browser. Try signing in again."
    is OAuthLoopbackException -> "Sign-in timed out or could not return to the app. Try again."
    is SecretStoreException -> "Unlock the system keyring and try again. No plaintext fallback was used."
    else -> "Unable to finish sign-in. Check your connection and account settings, then try again."
}
