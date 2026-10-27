package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import net.thunderbird.feature.account.AccountId

internal enum class ComposeField(val label: String) { TO("To"), SUBJECT("Subject"), BODY("Message") }

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
) {
    override fun toString(): String = "ComposerState(redacted)"
}

internal sealed interface ComposerEvent {
    data class Account(val id: AccountId) : ComposerEvent
    data object New : ComposerEvent
    data class Open(val id: String) : ComposerEvent
    data class Change(val field: ComposeField, val value: TextFieldValue) : ComposerEvent {
        override fun toString(): String = "ComposerEvent.Change(redacted)"
    }
    data class Keyboard(val field: ComposeField, val edit: MaliitTextEdit) : ComposerEvent {
        override fun toString(): String = "ComposerEvent.Keyboard(redacted)"
    }
    data object Save : ComposerEvent
    data object Queue : ComposerEvent
    data object Send : ComposerEvent
    data object Retry : ComposerEvent
    data object ConfirmRetry : ComposerEvent
    data object CancelRetry : ComposerEvent
    data object Cancel : ComposerEvent
    data object Back : ComposerEvent
}
