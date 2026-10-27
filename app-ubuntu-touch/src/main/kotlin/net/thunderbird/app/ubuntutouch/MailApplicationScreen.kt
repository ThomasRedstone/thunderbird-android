package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

@Composable
internal fun MailApplicationScreen(
    demo: DemoMailViewModel,
    keyboard: MaliitKeyboard?,
    createAccounts: () -> AccountSetupViewModel,
) {
    var showAccounts by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<AccountSetupViewModel?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(showAccounts) {
        if (showAccounts && accounts == null) {
            failed = false
            runCatching { withContext(Dispatchers.IO) { createAccounts() } }
                .onSuccess { accounts = it }
                .onFailure { failed = true }
        }
    }
    DisposableEffect(accounts) {
        val owned = accounts
        onDispose { owned?.close() }
    }
    val demoState by demo.state.collectAsState()
    val accountState = accounts?.state?.collectAsState()?.value
    val busy = accountState?.busy == true
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (showAccounts && event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                if (!busy) {
                    accounts?.event(AccountSetupEvent.Cancel)
                    showAccounts = false
                    keyboard?.focus(false)
                }
                true
            } else {
                false
            }
        },
    ) {
        ButtonText(
            if (showAccounts) "Back to demo mailbox" else "Accounts",
            {
                if (!busy) {
                    accounts?.event(AccountSetupEvent.Cancel)
                    showAccounts = !showAccounts
                    keyboard?.focus(false)
                }
            },
            enabled = !busy,
        )
        if (!showAccounts) {
            DemoMailScreen(demoState, demo::event, keyboard)
        } else {
            val loaded = accounts
            if (loaded != null) {
                AccountSetupScreen(checkNotNull(accountState), loaded::event, keyboard)
            } else {
                TextBodyLarge(
                    if (failed) "Unable to open account storage. Return and try again." else "Opening account storage…",
                )
            }
        }
    }
}
