package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.thunderbird.components.ui.bolt.atom.button.ButtonText

@Composable
internal fun ServerSearchPanel(
    mailbox: LiveMailState,
    model: ServerSearchViewModel?,
    keyboard: MaliitKeyboard?,
    reloadCache: () -> Unit,
    content: @Composable () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    val current = model?.state?.collectAsState()?.value
    val exit = {
        keyboard?.focus(false)
        searching = false
        reloadCache()
    }
    if (searching && current != null && model != null) {
        Column(
            Modifier.fillMaxSize().mailBack(!current.busy) {
                if (current.content == null) exit() else model.event(ServerSearchEvent.Back)
            },
        ) {
            ButtonText("Back to mailbox", exit, enabled = !current.busy)
            ServerSearchScreen(current, model::event, keyboard)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            val request = mailbox.serverSearchRequest()
            if (model != null && request != null) {
                ButtonText("Search this folder online", {
                    keyboard?.focus(false)
                    model.event(request)
                    searching = true
                })
            }
            content()
        }
    }
}

internal fun LiveMailState.serverSearchRequest(): ServerSearchEvent.Open? {
    val owner = account
    val readable = content == null && source == null && !searchAllFolders
    return if (!busy && readable && owner != null) folder?.let { ServerSearchEvent.Open(owner, it) } else null
}
