package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import net.thunderbird.feature.account.AccountId

internal enum class ComposeField(val label: String) {
    TO(
        "To",
    ),
    CC("Cc"),
    BCC("Bcc"),
    SUBJECT("Subject"),
    BODY("Message"),
}

internal data class ComposerState(
    val accounts: List<UtMailAccount>,
    val account: AccountId? = null,
    val items: List<OutgoingMail> = emptyList(),
    val selected: OutgoingMail? = null,
    val fields: Map<ComposeField, TextFieldValue> = emptyMap(),
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val cancelling: Boolean = false,
    val notice: String? = null,
    val confirmRetry: Boolean = false,
    val sentFolders: List<MailFolder> = emptyList(),
    val sentTarget: String? = null,
    val sentCopy: SentCopy? = null,
    val confirmCopy: Boolean = false,
    val showSentSettings: Boolean = false,
) {
    override fun toString(): String = "ComposerState(redacted)"
}

internal sealed interface ComposerEvent {
    data class Account(val id: AccountId) : ComposerEvent
    data class Resume(val request: ComposeRequest) : ComposerEvent
    data object New : ComposerEvent
    data class Open(val id: String) : ComposerEvent
    data class Change(val field: ComposeField, val value: TextFieldValue) : ComposerEvent {
        override fun toString(): String = "ComposerEvent.Change(redacted)"
    }
    data class Keyboard(val field: ComposeField, val edit: MaliitTextEdit) : ComposerEvent {
        override fun toString(): String = "ComposerEvent.Keyboard(redacted)"
    }
    data class SentFolder(val folder: String?) : ComposerEvent
    data object SentSettings : ComposerEvent
    data object FileSent : ComposerEvent
    data object ReviewCopy : ComposerEvent
    data object ConfirmCopy : ComposerEvent
    data object CancelCopy : ComposerEvent
    data object Save : ComposerEvent
    data object Queue : ComposerEvent
    data object Send : ComposerEvent
    data object Retry : ComposerEvent
    data object ConfirmRetry : ComposerEvent
    data object CancelRetry : ComposerEvent
    data object Cancel : ComposerEvent
    data object Back : ComposerEvent
}
