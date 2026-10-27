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

@Composable
internal fun ComposerScreen(state: ComposerState, onEvent: (ComposerEvent) -> Unit, keyboard: MaliitKeyboard?) {
    val height = keyboard?.height?.collectAsState()?.value ?: 0
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(0)
    val inset = with(LocalDensity.current) { height.coerceIn(0, windowHeight).toDp() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).padding(bottom = inset).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextTitleLarge("Drafts and outbox")
        if (state.account != null) {
            ButtonText("Back", {
                keyboard?.focus(false)
                onEvent(ComposerEvent.Back)
            }, enabled = !state.busy)
        }
        if (state.busy) TextBodyLarge("Working…")
        state.notice?.let { TextBodyLarge(it) }
        when {
            state.selected?.state == OutgoingState.DRAFT -> ComposerEditor(state, onEvent, keyboard)

            state.selected != null -> OutgoingDetails(state, onEvent)

            state.account != null -> {
                ButtonText("New message", { onEvent(ComposerEvent.New) }, enabled = !state.busy)
                if (state.items.isEmpty()) TextBodyLarge("No drafts or outgoing messages")
                state.items.forEach { item ->
                    ButtonText(
                        "${item.subject.ifBlank { "(No subject)" }} · ${outgoingLabel(item.state)}",
                        { onEvent(ComposerEvent.Open(item.id)) },
                        enabled = !state.busy,
                    )
                }
            }

            else -> {
                if (state.accounts.isEmpty()) TextBodyLarge("Add an account in Accounts to write mail.")
                state.accounts.forEach { account ->
                    ButtonText(account.displayName, { onEvent(ComposerEvent.Account(account.id)) })
                }
            }
        }
    }
}

@Composable
private fun ComposerEditor(state: ComposerState, onEvent: (ComposerEvent) -> Unit, keyboard: MaliitKeyboard?) {
    val focus = rememberComposerFocus(state, onEvent, keyboard)
    ComposeField.entries.forEach { field ->
        TextFieldOutlined(
            state.fields.getValue(field),
            { onEvent(ComposerEvent.Change(field, it)) },
            Modifier.fillMaxWidth().onFocusChanged { focus(field, it.isFocused) },
            label = field.label,
            isSingleLine = field != ComposeField.BODY,
            isEnabled = !state.busy,
        )
    }
    TextBodyLarge(if (state.dirty) "Unsaved changes · Saving automatically…" else "Draft saved locally")
    ButtonText("Save draft", { onEvent(ComposerEvent.Save) }, enabled = !state.busy)
    ButtonText("Queue message", {
        keyboard?.focus(false)
        onEvent(ComposerEvent.Queue)
    }, enabled = !state.busy)
    TextBodyLarge("Queueing saves the message offline. Use Send now in the outbox to contact the server.")
}

@Composable
private fun OutgoingDetails(state: ComposerState, onEvent: (ComposerEvent) -> Unit) {
    val item = checkNotNull(state.selected)
    TextTitleLarge(outgoingLabel(item.state))
    TextBodyLarge("To: ${item.recipients}")
    TextTitleLarge(item.subject.ifBlank { "(No subject)" })
    TextBodyLarge(item.body)
    when (item.state) {
        OutgoingState.QUEUED -> ButtonText("Send now", { onEvent(ComposerEvent.Send) }, enabled = !state.busy)

        OutgoingState.FAILED -> {
            TextBodyLarge("The server did not accept this attempt. Check the connection and account settings.")
            ButtonText("Retry sending", { onEvent(ComposerEvent.Retry) }, enabled = !state.busy)
        }

        OutgoingState.UNCERTAIN -> UncertainDelivery(state, onEvent)

        OutgoingState.SENT -> TextBodyLarge("Accepted by SMTP. Sent-folder filing is not available yet.")

        OutgoingState.SENDING -> TextBodyLarge("An attempt is in progress. It will not be sent again automatically.")

        OutgoingState.DRAFT -> Unit
    }
}

@Composable
private fun UncertainDelivery(state: ComposerState, onEvent: (ComposerEvent) -> Unit) {
    TextBodyLarge("Delivery is uncertain. The server may already have accepted this message. Check before retrying.")
    if (state.confirmRetry) {
        TextBodyLarge("Sending again may deliver a duplicate. Do you want to send another attempt?")
        ButtonText("Send again despite duplicate risk", { onEvent(ComposerEvent.ConfirmRetry) }, enabled = !state.busy)
        ButtonText("Keep uncertain", { onEvent(ComposerEvent.CancelRetry) }, enabled = !state.busy)
    } else {
        ButtonText("Review retry", { onEvent(ComposerEvent.Retry) }, enabled = !state.busy)
    }
}

internal fun outgoingLabel(state: OutgoingState): String = when (state) {
    OutgoingState.DRAFT -> "Draft"
    OutgoingState.QUEUED -> "Queued"
    OutgoingState.SENDING -> "Sending"
    OutgoingState.SENT -> "Accepted by SMTP"
    OutgoingState.FAILED -> "Failed"
    OutgoingState.UNCERTAIN -> "Uncertain delivery"
}

@Composable
private fun rememberComposerFocus(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    keyboard: MaliitKeyboard?,
): (ComposeField, Boolean) -> Unit {
    var focused by remember { mutableStateOf<ComposeField?>(null) }
    val latest by rememberUpdatedState(state)
    val focusManager = LocalFocusManager.current
    DisposableEffect(keyboard) { onDispose { keyboard?.focus(false) } }
    LaunchedEffect(keyboard) {
        keyboard?.events?.collect { edit ->
            val field = focused
            if (field != null && keyboard.focused && !latest.busy) {
                if (edit == MaliitTextEdit.Enter && field != ComposeField.BODY) {
                    focusManager.clearFocus()
                    keyboard.focus(false)
                } else {
                    val input = if (edit == MaliitTextEdit.Enter) MaliitTextEdit.Commit("\n") else edit
                    onEvent(ComposerEvent.Keyboard(field, input))
                }
            }
        }
    }
    return { field, hasFocus ->
        if (hasFocus) {
            focused = field
        } else if (focused == field) {
            focused = null
        }
        keyboard?.focus(hasFocus)
    }
}
