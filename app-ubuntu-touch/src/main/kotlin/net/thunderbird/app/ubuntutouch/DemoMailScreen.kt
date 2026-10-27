package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlined

@Composable
internal fun DemoMailScreen(viewModel: DemoMailViewModel, keyboard: MaliitKeyboard? = null) {
    val state by viewModel.state.collectAsState()
    DemoMailScreen(state, viewModel::event, keyboard)
}

@Composable
internal fun DemoMailScreen(state: DemoMailState, onEvent: (DemoMailEvent) -> Unit, keyboard: MaliitKeyboard?) {
    val height = keyboard?.height?.collectAsState()?.value ?: 0
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(0)
    val keyboardInset = with(LocalDensity.current) { height.coerceIn(0, windowHeight).toDp() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).padding(bottom = keyboardInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextTitleLarge("Mail demo")
        TextBodyLarge("Offline preview · Sample messages only")
        if (state.folder != null) {
            ButtonText("Back", { onEvent(DemoMailEvent.Back) }, Modifier.heightIn(min = 48.dp))
        }
        val message = state.message
        when {
            message != null -> DemoReader(message, Modifier.weight(1f))

            state.folder != null -> DemoMessageList(state, onEvent, keyboard, Modifier.weight(1f))

            else -> {
                TextTitleLarge("Folders")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.folders, key = { it.id }) { item ->
                        ButtonText(
                            text = "${item.name} (${item.messages.size})",
                            onClick = { onEvent(DemoMailEvent.OpenFolder(item)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoReader(message: DemoMessage, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextTitleLarge(message.subject)
            TextBodyLarge(message.sender)
            TextBodyLarge(message.body)
        }
    }
}

@Composable
private fun DemoMessageList(
    state: DemoMailState,
    onEvent: (DemoMailEvent) -> Unit,
    keyboard: MaliitKeyboard?,
    modifier: Modifier = Modifier,
) {
    val folder = checkNotNull(state.folder)
    var editing by rememberMaliitEditor(folder.id, state.query, keyboard) {
        onEvent(DemoMailEvent.Search(it))
    }
    val unavailable = keyboard?.unavailable?.collectAsState()?.value ?: false
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextTitleLarge(folder.name)
        TextFieldOutlined(
            value = editing,
            onValueChange = {
                editing = it
                onEvent(DemoMailEvent.Search(it.text))
            },
            label = "Filter subjects",
            modifier = Modifier.fillMaxWidth().onFocusChanged { keyboard?.focus(it.isFocused) },
        )
        if (unavailable) TextBodyLarge("On-screen keyboard is unavailable")
        val messages = folder.messages.filter { it.subject.contains(state.query, ignoreCase = true) }
        if (messages.isEmpty()) TextBodyLarge("No messages")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { item ->
                ButtonText(
                    text = "${item.subject}\n${item.sender}",
                    onClick = { onEvent(DemoMailEvent.OpenMessage(item)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberMaliitEditor(
    folderId: String,
    query: String,
    keyboard: MaliitKeyboard?,
    onQueryChange: (String) -> Unit,
): MutableState<TextFieldValue> {
    val editing = remember(folderId) { mutableStateOf(TextFieldValue(query)) }
    val updateQuery by rememberUpdatedState(onQueryChange)
    val focusManager = LocalFocusManager.current
    DisposableEffect(keyboard) {
        onDispose { keyboard?.focus(false) }
    }
    LaunchedEffect(keyboard) {
        keyboard?.events?.collect { event ->
            if (keyboard.focused) {
                if (event == MaliitTextEdit.Enter) {
                    focusManager.clearFocus()
                    keyboard.focus(false)
                } else {
                    editing.value = applyMaliitEdit(editing.value, event)
                    updateQuery(editing.value.text)
                }
            }
        }
    }
    return editing
}
