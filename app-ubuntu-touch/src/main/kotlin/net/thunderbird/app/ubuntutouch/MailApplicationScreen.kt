package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

private enum class MailDestination { DEMO, ACCOUNTS, MAILBOX, COMPOSE }

@Composable
internal fun MailApplicationScreen(
    demo: DemoMailViewModel,
    keyboard: MaliitKeyboard?,
    createAccounts: () -> AccountSetupViewModel,
    createMailbox: () -> LiveMailViewModel,
    createComposer: () -> ComposerViewModel,
    lifecycle: MailWindowLifecycle,
) {
    var destination by remember { mutableStateOf(MailDestination.DEMO) }
    when (destination) {
        MailDestination.DEMO -> {
            Column(Modifier.fillMaxSize()) {
                NavigationButtons(false, { destination = it }, keyboard)
                val state by demo.state.collectAsState()
                DemoMailScreen(state, demo::event, keyboard)
            }
        }

        MailDestination.ACCOUNTS -> {
            val accounts = loadOwnedMailModel(createAccounts)
            if (accounts != null) {
                val state by accounts.state.collectAsState()
                Column(
                    Modifier.fillMaxSize().mailBack(!state.busy) {
                        if (state.editing) {
                            accounts.event(AccountSetupEvent.Cancel)
                        } else {
                            destination = MailDestination.DEMO
                        }
                        keyboard?.focus(false)
                    },
                ) {
                    NavigationButtons(state.busy, { destination = it }, keyboard)
                    AccountSetupScreen(state, accounts::event, keyboard)
                }
            }
        }

        MailDestination.COMPOSE -> {
            val composer = loadOwnedMailModel(createComposer)
            if (composer != null) {
                DisposableEffect(composer, lifecycle) {
                    val registration = lifecycle.register(composer::flushForClose, composer::flushOnBackground)
                    onDispose { registration.close() }
                }
                val state by composer.state.collectAsState()
                ComposerDestination(state, composer::event, keyboard) { destination = it }
            }
        }

        MailDestination.MAILBOX -> {
            val mailbox = loadOwnedMailModel(createMailbox)
            if (mailbox != null) {
                val state by mailbox.state.collectAsState()
                Column(
                    Modifier.fillMaxSize().mailBack(!state.busy) {
                        if (state.account == null) {
                            destination = MailDestination.DEMO
                        } else {
                            mailbox.event(LiveMailEvent.Back)
                        }
                    },
                ) {
                    NavigationButtons(state.busy, { destination = it }, keyboard)
                    LiveMailScreen(state, mailbox::event)
                }
            }
        }
    }
}

@Composable
private fun NavigationButtons(busy: Boolean, navigate: (MailDestination) -> Unit, keyboard: MaliitKeyboard?) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        MailDestination.entries.forEach { destination ->
            ButtonText(
                when (destination) {
                    MailDestination.DEMO -> "Demo mailbox"
                    MailDestination.ACCOUNTS -> "Accounts"
                    MailDestination.MAILBOX -> "Mailboxes"
                    MailDestination.COMPOSE -> "Write"
                },
                {
                    keyboard?.focus(false)
                    navigate(destination)
                },
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun <T : AutoCloseable> loadOwnedMailModel(create: () -> T): T? {
    var loaded by remember { mutableStateOf<T?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            loaded = withContext(Dispatchers.IO) { create() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            failed = true
        }
    }
    DisposableEffect(loaded) {
        val owned = loaded
        onDispose { owned?.close() }
    }
    val current = loaded
    if (current == null) {
        TextBodyLarge(if (failed) "Unable to open storage. Reopen the app to retry." else "Opening storage…")
    }
    return current
}

private fun Modifier.mailBack(enabled: Boolean, back: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
        if (enabled) back()
        true
    } else {
        false
    }
}

@Composable
private fun ComposerDestination(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    keyboard: MaliitKeyboard?,
    navigate: (MailDestination) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().mailBack(!state.busy) {
            keyboard?.focus(false)
            if (state.account == null) navigate(MailDestination.DEMO) else onEvent(ComposerEvent.Back)
        },
    ) {
        NavigationButtons(state.busy || state.dirty, navigate, keyboard)
        ComposerScreen(state, onEvent, keyboard)
    }
}
