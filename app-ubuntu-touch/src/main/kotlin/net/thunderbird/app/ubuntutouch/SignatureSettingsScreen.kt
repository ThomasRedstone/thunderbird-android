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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlined

@Composable
internal fun SignatureSettingsScreen(
    state: SignatureSettingsState,
    onEvent: (SignatureSettingsEvent) -> Unit,
    keyboard: MaliitKeyboard?,
) {
    val height = keyboard?.height?.collectAsState()?.value ?: 0
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(0)
    val inset = with(LocalDensity.current) { height.coerceIn(0, windowHeight).toDp() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).padding(bottom = inset).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextTitleLarge("Account signatures")
        TextBodyLarge("Plain text · Applies only to newly created drafts. Saved drafts keep their text.")
        state.notice?.let { TextBodyLarge(it) }
        if (state.account == null) {
            if (state.accounts.isEmpty()) TextBodyLarge("Add an account in Accounts to configure its signature.")
            state.accounts.forEach { account ->
                ButtonText(account.displayName, {
                    onEvent(SignatureSettingsEvent.Account(account.id))
                }, enabled = !state.busy)
            }
        } else {
            SignatureEditor(state, onEvent, keyboard)
        }
    }
}

@Composable
private fun SignatureEditor(
    state: SignatureSettingsState,
    onEvent: (SignatureSettingsEvent) -> Unit,
    keyboard: MaliitKeyboard?,
) {
    val focus = rememberSignatureFocus(state, onEvent, keyboard)
    TextBodyLarge(state.accounts.find { it.id == state.account }?.displayName ?: "Account unavailable")
    ButtonText("Back to accounts", { onEvent(SignatureSettingsEvent.Back) }, enabled = !state.busy)
    TextFieldOutlined(
        state.input,
        { onEvent(SignatureSettingsEvent.Text(it)) },
        Modifier.fillMaxWidth().onFocusChanged { focus(it.isFocused) },
        label = "Plain-text signature",
        isSingleLine = false,
        isEnabled = !state.busy,
    )
    SignaturePreference("Signature", state.signature.enabled, !state.busy) { onEvent(SignatureSettingsEvent.Enabled) }
    SignaturePreference("Include on replies", state.signature.replies, !state.busy) {
        onEvent(SignatureSettingsEvent.Replies)
    }
    SignaturePreference("Include on forwards", state.signature.forwards, !state.busy) {
        onEvent(SignatureSettingsEvent.Forwards)
    }
    TextBodyLarge(if (state.dirty) "Unsaved signature edits" else "Signature settings saved")
    ButtonText("Save signature", {
        keyboard?.focus(false)
        onEvent(SignatureSettingsEvent.Save)
    }, enabled = !state.busy && state.dirty)
    if (state.confirmDiscard) {
        TextBodyLarge("Discard unsaved signature edits? Your saved signature and drafts are kept.")
        ButtonText("Discard signature edits", { onEvent(SignatureSettingsEvent.ConfirmDiscard) }, enabled = !state.busy)
        ButtonText("Keep editing", { onEvent(SignatureSettingsEvent.Keep) }, enabled = !state.busy)
    } else if (state.dirty) {
        ButtonText("Review discarding edits", { onEvent(SignatureSettingsEvent.ReviewDiscard) }, enabled = !state.busy)
    }
}

@Composable
private fun SignaturePreference(label: String, selected: Boolean, enabled: Boolean, change: () -> Unit) {
    ButtonText("$label: ${if (selected) "on" else "off"}", change, enabled = enabled)
}

@Composable
private fun rememberSignatureFocus(
    state: SignatureSettingsState,
    onEvent: (SignatureSettingsEvent) -> Unit,
    keyboard: MaliitKeyboard?,
): (Boolean) -> Unit {
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    DisposableEffect(keyboard) { onDispose { keyboard?.focus(false) } }
    LaunchedEffect(keyboard) {
        keyboard?.events?.collect { edit ->
            if (focused && keyboard.focused && !latest.busy) onEvent(SignatureSettingsEvent.Keyboard(edit))
        }
    }
    return { value ->
        focused = value
        keyboard?.focus(value)
    }
}
