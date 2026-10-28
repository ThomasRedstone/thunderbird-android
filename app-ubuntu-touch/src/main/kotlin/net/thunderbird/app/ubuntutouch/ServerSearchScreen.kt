package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
internal fun ServerSearchScreen(
    state: ServerSearchState,
    onEvent: (ServerSearchEvent) -> Unit,
    keyboard: MaliitKeyboard? = null,
) {
    val height = keyboard?.height?.collectAsState()?.value ?: 0
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(0)
    val inset = with(LocalDensity.current) { height.coerceIn(0, windowHeight).toDp() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).padding(bottom = inset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextTitleLarge("Search server")
        if (state.account != null) TextBodyLarge("${state.accountName} · ${state.folder?.name.orEmpty()}")
        state.notice?.let { TextBodyLarge(it) }
        if (state.busy) {
            TextBodyLarge(if (state.cancelling) "Cancelling…" else "Contacting server…")
            ButtonText("Cancel", { onEvent(ServerSearchEvent.Cancel) }, enabled = !state.cancelling)
        }
        val content = state.content
        if (content != null) {
            ButtonText("Back to results", { onEvent(ServerSearchEvent.Back) }, enabled = !state.busy)
            MailReader(content, state.accountName, Modifier.weight(1f))
        } else {
            val results = state.results
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ServerSearchForm(state, onEvent, keyboard)
                    }
                }
                if (results != null) {
                    item {
                        TextBodyLarge(
                            "Showing ${results.messages.size} of ${results.matched} matches · " +
                                "Most recently added first",
                        )
                        if (results.limited) {
                            TextBodyLarge("Results are limited. Refine your search to find other messages.")
                        }
                    }
                    items(results.messages, key = { it.id }) { message ->
                        ButtonText(
                            "${message.subject.ifBlank { "(No subject)" }} · ${message.sender}",
                            {
                                keyboard?.focus(false)
                                onEvent(ServerSearchEvent.Read(message.id))
                            },
                            enabled = !state.busy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerSearchForm(
    state: ServerSearchState,
    onEvent: (ServerSearchEvent) -> Unit,
    keyboard: MaliitKeyboard?,
) {
    val focus = rememberServerSearchFocus(state, onEvent, keyboard)
    TextBodyLarge("Online search sends your query to this folder's mail server.")
    TextFieldOutlined(
        state.input,
        { onEvent(ServerSearchEvent.Text(it)) },
        Modifier.fillMaxWidth().onFocusChanged { focus(it.isFocused) },
        label = "Server search query",
        isSingleLine = true,
        isEnabled = !state.busy,
    )
    ButtonText(
        if (state.fullText) "Search headers and message text" else "Search subject and address headers",
        { onEvent(ServerSearchEvent.FullText) },
        enabled = !state.busy,
    )
    ButtonText("Search online", {
        keyboard?.focus(false)
        onEvent(ServerSearchEvent.Search)
    }, enabled = !state.busy && state.account != null && state.input.text.isNotBlank())
    TextBodyLarge("Matching follows your server's search rules. Opening a result downloads and caches that message.")
}

@Composable
private fun rememberServerSearchFocus(
    state: ServerSearchState,
    onEvent: (ServerSearchEvent) -> Unit,
    keyboard: MaliitKeyboard?,
): (Boolean) -> Unit {
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    DisposableEffect(keyboard) { onDispose { keyboard?.focus(false) } }
    LaunchedEffect(keyboard) {
        keyboard?.events?.collect { edit ->
            if (focused && keyboard.focused && !latest.busy) onEvent(ServerSearchEvent.Keyboard(edit))
        }
    }
    return { value ->
        focused = value
        keyboard?.focus(value)
    }
}
