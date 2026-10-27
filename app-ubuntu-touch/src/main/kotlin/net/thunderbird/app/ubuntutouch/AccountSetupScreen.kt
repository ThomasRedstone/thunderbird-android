package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlined
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlinedPassword

@Composable
internal fun AccountSetupScreen(
    state: AccountSetupState,
    onEvent: (AccountSetupEvent) -> Unit,
    keyboard: MaliitKeyboard?,
) {
    val height = keyboard?.height?.collectAsState()?.value ?: 0
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(0)
    val inset = with(LocalDensity.current) { height.coerceIn(0, windowHeight).toDp() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).padding(bottom = inset).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextTitleLarge(if (state.editing) "Account setup" else "Accounts")
        state.message?.let { TextBodyLarge(it) }
        if (state.busy) TextBodyLarge("Saving changes…")
        when {
            state.editing -> AccountForm(state, onEvent, keyboard)

            state.pendingRemoval != null -> {
                TextBodyLarge(
                    "Remove this account, its saved passwords and local cached mail? Mail on the server will remain.",
                )
                ButtonText("Remove account", {
                    onEvent(AccountSetupEvent.ConfirmRemoval)
                }, enabled = !state.busy)
                ButtonText("Keep account", {
                    onEvent(AccountSetupEvent.CancelRemoval)
                }, enabled = !state.busy)
            }

            else -> AccountList(state, onEvent)
        }
    }
}

@Composable
private fun AccountList(state: AccountSetupState, onEvent: (AccountSetupEvent) -> Unit) {
    TextBodyLarge("Manual IMAP/SMTP setup. This preview saves accounts securely; network mail is not connected yet.")
    state.accounts.forEach { account ->
        TextBodyLarge(account.displayName)
        ButtonText("Edit ${account.displayName}", { onEvent(AccountSetupEvent.Edit(account.id)) })
        ButtonText("Remove ${account.displayName}", { onEvent(AccountSetupEvent.RequestRemoval(account.id)) })
    }
    ButtonText("Add account", { onEvent(AccountSetupEvent.New) })
}

@Composable
private fun AccountForm(state: AccountSetupState, onEvent: (AccountSetupEvent) -> Unit, keyboard: MaliitKeyboard?) {
    val focusManager = LocalFocusManager.current
    val onFocus = rememberAccountFocus(state, onEvent, keyboard)
    if (state.editingId != null) TextBodyLarge("Leave both password fields blank to keep your saved passwords.")
    AccountField.entries.forEach { field ->
        val modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
            onFocus(field, focus.isFocused)
        }
        val change = { value: androidx.compose.ui.text.input.TextFieldValue ->
            onEvent(AccountSetupEvent.Change(field, value))
        }
        if (field.secret) {
            TextFieldOutlinedPassword(
                state.fields.getValue(field),
                change,
                modifier,
                label = field.label,
                isEnabled = !state.busy,
            )
        } else {
            TextFieldOutlined(
                state.fields.getValue(field),
                change,
                modifier,
                label = field.label,
                isEnabled = !state.busy,
            )
        }
        when (field) {
            AccountField.IMAP_PORT -> ButtonText(
                if (state.imapStartTls) "IMAP: STARTTLS required" else "IMAP: direct TLS",
                { onEvent(AccountSetupEvent.StartTls(incoming = true, enabled = !state.imapStartTls)) },
                enabled = !state.busy,
            )

            AccountField.SMTP_PORT -> ButtonText(
                if (state.smtpStartTls) "SMTP: STARTTLS required" else "SMTP: direct TLS",
                { onEvent(AccountSetupEvent.StartTls(incoming = false, enabled = !state.smtpStartTls)) },
                enabled = !state.busy,
            )

            else -> Unit
        }
    }
    ButtonText("Save account", {
        focusManager.clearFocus()
        onEvent(AccountSetupEvent.Save)
    }, enabled = !state.busy)
    ButtonText("Cancel", {
        focusManager.clearFocus()
        onEvent(AccountSetupEvent.Cancel)
    }, enabled = !state.busy)
}

@Composable
private fun rememberAccountFocus(
    state: AccountSetupState,
    onEvent: (AccountSetupEvent) -> Unit,
    keyboard: MaliitKeyboard?,
): (AccountField, Boolean) -> Unit {
    var focusedField by remember { mutableStateOf<AccountField?>(null) }
    val latestState by rememberUpdatedState(state)
    val focusManager = LocalFocusManager.current
    DisposableEffect(keyboard) {
        onDispose { keyboard?.focus(false) }
    }
    LaunchedEffect(keyboard) {
        keyboard?.events?.collect { event ->
            val field = focusedField
            if (field != null && keyboard.focused && !latestState.busy) {
                if (event == MaliitTextEdit.Enter) {
                    focusManager.clearFocus()
                } else {
                    onEvent(AccountSetupEvent.KeyboardEdit(field, event))
                }
            }
        }
    }
    return { field, focused ->
        if (focused) {
            focusedField = field
        } else if (focusedField == field) {
            focusedField = null
        }
        keyboard?.focus(focused, hiddenText = field.secret)
    }
}
