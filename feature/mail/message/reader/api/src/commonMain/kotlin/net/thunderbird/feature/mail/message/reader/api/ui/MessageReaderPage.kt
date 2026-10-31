package net.thunderbird.feature.mail.message.reader.api.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge

/** The platform host supplies content from its own account-scoped message repository. */
data class MessageReaderPageContent(
    val subject: String,
    val sender: String,
    val body: String,
    val accountName: String? = null,
    val complete: Boolean = true,
    val plainText: Boolean = true,
    val unread: Boolean = false,
    val starred: Boolean = false,
    val previousAvailable: Boolean = false,
    val nextAvailable: Boolean = false,
    val busy: Boolean = false,
) {
    override fun toString(): String = "MessageReaderPageContent(redacted)"
}

enum class MessageReaderPageAction {
    DOWNLOAD,
    REPLY,
    REPLY_ALL,
    FORWARD,
    TOGGLE_READ,
    TOGGLE_STAR,
    PREVIOUS,
    NEXT,
}

/** Reader content shared by mobile hosts; actions and message loading stay with the host. */
@Composable
fun MessageReaderPage(
    content: MessageReaderPageContent,
    modifier: Modifier = Modifier,
    onAction: ((MessageReaderPageAction) -> Unit)? = null,
    attachments: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), Arrangement.spacedBy(12.dp)) {
        if (!content.complete) {
            TextBodyLarge("Only a preview is cached.")
            if (onAction != null) {
                ButtonText("Download full message", {
                    onAction(MessageReaderPageAction.DOWNLOAD)
                }, enabled = !content.busy)
            }
        }
        TextTitleLarge(content.subject)
        TextBodyLarge(content.sender)
        content.accountName?.let { TextBodyLarge("Account: $it") }
        if (onAction != null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ButtonText(
                    if (content.unread) "Mark read" else "Mark unread",
                    { onAction(MessageReaderPageAction.TOGGLE_READ) },
                    enabled = !content.busy,
                )
                ButtonText(
                    if (content.starred) "Remove star" else "Star message",
                    { onAction(MessageReaderPageAction.TOGGLE_STAR) },
                    enabled = !content.busy,
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ButtonText("Reply", { onAction(MessageReaderPageAction.REPLY) }, enabled = content.canRespond)
                ButtonText("Reply all", { onAction(MessageReaderPageAction.REPLY_ALL) }, enabled = content.canRespond)
                ButtonText("Forward text", { onAction(MessageReaderPageAction.FORWARD) }, enabled = content.canRespond)
            }
            if (!content.plainText) TextBodyLarge("Reply and forward require a cached plain-text body in this preview.")
            Row {
                ButtonText(
                    "Previous message",
                    { onAction(MessageReaderPageAction.PREVIOUS) },
                    enabled = !content.busy && content.previousAvailable,
                )
                ButtonText(
                    "Next message",
                    { onAction(MessageReaderPageAction.NEXT) },
                    enabled = !content.busy && content.nextAvailable,
                )
            }
        }
        SelectionContainer { TextBodyLarge(content.body) }
        attachments()
    }
}

private val MessageReaderPageContent.canRespond: Boolean
    get() = !busy && complete && plainText

/** Match the mobile reader's order to the current folder list; never cross a list boundary. */
fun <T> adjacentMessage(messages: List<T>, current: T, offset: Int): T? {
    require(offset == -1 || offset == 1)
    val position = messages.indexOf(current)
    return if (position < 0) null else messages.getOrNull(position + offset)
}
