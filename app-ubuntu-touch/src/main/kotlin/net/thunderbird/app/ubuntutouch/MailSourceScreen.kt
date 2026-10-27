package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

@Composable
internal fun MailSourceScreen(
    page: MailSourcePage,
    busy: Boolean,
    onEvent: (LiveMailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextBodyLarge(if (page.complete) "Cached message source" else "Cached message source · Incomplete message")
        TextBodyLarge(
            if (page.totalBytes == 0L) {
                "0 bytes"
            } else {
                "Bytes ${page.offset + 1}–${page.offset + page.sizeBytes} of ${page.totalBytes}"
            },
        )
        TextBodyLarge("Non-ASCII and control bytes use \\xHH; backslashes are doubled. Line breaks are preserved.")
        Row {
            ButtonText(
                "Previous page",
                { page.previousOffset?.let { onEvent(LiveMailEvent.Source(it)) } },
                enabled = !busy && page.previousOffset != null,
            )
            ButtonText(
                "Next page",
                { page.nextOffset?.let { onEvent(LiveMailEvent.Source(it)) } },
                enabled = !busy && page.nextOffset != null,
            )
        }
        key(page.offset) {
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TextBodyLarge(page.text)
            }
        }
    }
}

@Composable
internal fun ReaderNavigationControls(state: LiveMailState, onEvent: (LiveMailEvent) -> Unit) {
    if (state.source == null) {
        MailboxControls(state, onEvent)
    } else {
        ButtonText("Back to message", { onEvent(LiveMailEvent.Back) }, enabled = !state.busy)
        state.notice?.let { TextBodyLarge(it) }
    }
}
