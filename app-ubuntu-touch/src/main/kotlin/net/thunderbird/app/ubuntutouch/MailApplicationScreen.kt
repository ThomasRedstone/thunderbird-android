package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private enum class MailDestination { DEMO, ACCOUNTS, MAILBOX, COMPOSE, SIGNATURES }

@Composable
internal fun MailApplicationScreen(
    demo: DemoMailViewModel,
    keyboard: MaliitKeyboard?,
    createAccounts: () -> AccountSetupViewModel,
    createMailbox: () -> LiveMailViewModel,
    createComposer: () -> ComposerViewModel,
    lifecycle: MailWindowLifecycle,
    createSave: (() -> MessageSaveRuntime)? = null,
    createSignatures: (() -> SignatureSettingsViewModel)? = null,
    createMoves: (() -> MailMoveViewModel)? = null,
) {
    val save = createSave?.let { loadOwnedMailModel(it)?.model }
    if (save != null) RegisterMailClose(save, lifecycle, save::flushForClose, {})
    val moves = createMoves?.let { loadOwnedMailModel(it) }
    if (moves != null) RegisterMailClose(moves, lifecycle, moves::flushForClose, moves::background)
    val transfers = collectMailboxTransfers(save, moves)
    var destination by remember { mutableStateOf(MailDestination.DEMO) }
    var composeRequest by remember { mutableStateOf<ComposeRequest?>(null) }
    when (destination) {
        MailDestination.DEMO -> {
            Column(Modifier.fillMaxSize()) {
                NavigationButtons(false, { destination = it }, keyboard)
                val state by demo.state.collectAsState()
                DemoMailScreen(state, demo::event, keyboard)
            }
        }

        MailDestination.ACCOUNTS -> AccountsDestination({ createAccounts() }, keyboard, lifecycle) { destination = it }

        MailDestination.SIGNATURES -> SignatureSettingsDestination(createSignatures, keyboard, lifecycle) {
            destination =
                it
        }

        MailDestination.COMPOSE -> {
            val composer = loadOwnedMailModel(createComposer)
            if (composer != null) {
                RegisterMailClose(composer, lifecycle, composer::flushForClose, composer::flushOnBackground)
                LaunchedEffect(composer, composeRequest) {
                    composeRequest?.let { composer.event(ComposerEvent.Resume(it)) }
                    composeRequest = null
                }
                val state by composer.state.collectAsState()
                ComposerDestination(state, composer::event, keyboard) { destination = it }
            }
        }

        MailDestination.MAILBOX -> {
            val mailbox = loadOwnedMailModel(createMailbox)
            if (mailbox != null) {
                RegisterMailClose(mailbox, lifecycle, mailbox::flushForClose, {})
                val state by mailbox.state.collectAsState()
                MailboxDestination(
                    state,
                    mailbox::event,
                    keyboard,
                    MailboxNavigation({ destination = it }, {
                        composeRequest = it
                        destination = MailDestination.COMPOSE
                    }),
                    transfers,
                )
            }
        }
    }
}

@Composable
private fun AccountsDestination(
    create: () -> AccountSetupViewModel,
    keyboard: MaliitKeyboard?,
    lifecycle: MailWindowLifecycle,
    navigate: (MailDestination) -> Unit,
) {
    val accounts = loadOwnedMailModel(create)
    if (accounts != null) {
        RegisterMailClose(accounts, lifecycle, accounts::flushForClose, {})
        val state by accounts.state.collectAsState()
        Column(
            Modifier.fillMaxSize().mailBack(!state.busy) {
                if (state.editing) {
                    accounts.event(AccountSetupEvent.Cancel)
                } else {
                    navigate(MailDestination.DEMO)
                }
                keyboard?.focus(false)
            },
        ) {
            NavigationButtons(state.busy, navigate, keyboard)
            AccountSetupScreen(state, accounts::event, keyboard)
        }
    }
}

@Composable
private fun SignatureSettingsDestination(
    create: (() -> SignatureSettingsViewModel)?,
    keyboard: MaliitKeyboard?,
    lifecycle: MailWindowLifecycle,
    navigate: (MailDestination) -> Unit,
) {
    val signatures = create?.let { loadOwnedMailModel(it) } ?: return
    RegisterMailClose(signatures, lifecycle, signatures::flushForClose, signatures::flushOnBackground)
    val state by signatures.state.collectAsState()
    Column(
        Modifier.fillMaxSize().mailBack(!state.busy) {
            if (state.account == null) {
                navigate(MailDestination.DEMO)
            } else {
                signatures.event(SignatureSettingsEvent.Back)
            }
        },
    ) {
        NavigationButtons(state.busy || state.dirty, navigate, keyboard)
        SignatureSettingsScreen(state, signatures::event, keyboard)
    }
}

private class MailboxNavigation(val navigate: (MailDestination) -> Unit, val compose: (ComposeRequest) -> Unit)
private class MailboxTransfers(
    val save: MessageSaveState?,
    val saveEvent: (MessageSaveEvent) -> Unit,
    val moves: MailMoveUiState?,
    val moveEvent: (MailMoveEvent) -> Unit,
)

@Composable
private fun collectMailboxTransfers(save: MessageSaveViewModel?, moves: MailMoveViewModel?): MailboxTransfers =
    MailboxTransfers(
        save?.state?.collectAsState()?.value,
        { save?.event(it) },
        moves?.state?.collectAsState()?.value,
        { moves?.event(it) },
    )

@Composable
private fun MailboxDestination(
    state: LiveMailState,
    onEvent: (LiveMailEvent) -> Unit,
    keyboard: MaliitKeyboard?,
    navigation: MailboxNavigation,
    transfers: MailboxTransfers,
) {
    var saving by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val saveState = transfers.save
    val moveState = transfers.moves
    val exitMove = {
        moving = false
        onEvent(LiveMailEvent.ReloadCached)
    }
    LaunchedEffect(state.composeRequest) { state.composeRequest?.let(navigation.compose) }
    Column(
        Modifier.fillMaxSize().mailBack(!state.busy && moveState?.busy != true) {
            when {
                moving -> exitMove()
                saving -> saving = false
                state.account == null -> navigation.navigate(MailDestination.DEMO)
                else -> onEvent(LiveMailEvent.Back)
            }
        },
    ) {
        when {
            moving && moveState != null -> {
                ButtonText("Back to mailbox", exitMove, enabled = !moveState.busy)
                MailMoveScreen(moveState, transfers.moveEvent)
            }

            saving && saveState != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                ButtonText("Back to mailbox", { saving = false })
                MessageSaveControls(saveState, transfers.saveEvent)
            }

            else -> MailboxContent(
                state,
                onEvent,
                keyboard,
                navigation,
                transfers,
                MailboxPanels({ saving = true }, { moving = true }),
            )
        }
    }
}

private class MailboxPanels(val save: () -> Unit, val move: () -> Unit)

@Composable
private fun MailboxContent(
    state: LiveMailState,
    onEvent: (LiveMailEvent) -> Unit,
    keyboard: MaliitKeyboard?,
    navigation: MailboxNavigation,
    transfers: MailboxTransfers,
    panels: MailboxPanels,
) {
    val saveState = transfers.save
    NavigationButtons(state.busy, navigation.navigate, keyboard)
    if (saveState?.pending?.record != null || saveState?.notice != null) {
        ButtonText("Review message transfer", panels.save)
    }
    if (transfers.moves != null) {
        ButtonText("Move history", {
            transfers.moveEvent(MailMoveEvent.Open())
            panels.move()
        })
    }
    LiveMailScreen(
        state,
        keyboard,
        onSave = saveState?.let { saved ->
            { request ->
                keyboard?.focus(false)
                if (!saved.busy && saved.pending?.record == null) transfers.saveEvent(request)
                panels.save()
            }
        },
        onMove = transfers.moves?.let {
            { key ->
                keyboard?.focus(false)
                transfers.moveEvent(MailMoveEvent.Open(key))
                panels.move()
            }
        },
        onEvent = onEvent,
    )
}

@Composable
private fun RegisterMailClose(
    owner: AutoCloseable,
    lifecycle: MailWindowLifecycle,
    save: suspend () -> Boolean,
    background: () -> Unit,
) {
    DisposableEffect(owner, lifecycle) {
        val registration = lifecycle.register(save, background)
        onDispose { registration.close() }
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
                    MailDestination.SIGNATURES -> "Signatures"
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
