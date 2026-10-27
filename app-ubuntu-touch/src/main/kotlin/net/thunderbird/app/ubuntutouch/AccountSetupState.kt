package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.input.TextFieldValue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory

internal enum class AccountField(val label: String, val secret: Boolean = false, val initial: String = "") {
    NAME("Account name"),
    ADDRESS("Email address"),
    IMAP_HOST("IMAP hostname"),
    IMAP_PORT("IMAP port", initial = "993"),
    IMAP_USER("IMAP username"),
    IMAP_PASSWORD("IMAP password", secret = true),
    SMTP_HOST("SMTP hostname"),
    SMTP_PORT("SMTP port", initial = "465"),
    SMTP_USER("SMTP username"),
    SMTP_PASSWORD("SMTP password", secret = true),
}

@Suppress("LongParameterList") // Immutable UI state, never serialised or logged.
internal data class AccountSetupState(
    val accounts: List<UtMailAccount>,
    val editing: Boolean = false,
    val editingId: AccountId? = null,
    val fields: Map<AccountField, TextFieldValue> = AccountField.entries.associateWith { TextFieldValue(it.initial) },
    val imapStartTls: Boolean = false,
    val smtpStartTls: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingRemoval: AccountId? = null,
    val providers: List<OAuthProviderChoice> = emptyList(),
    val providerId: String? = null,
    val signingIn: Boolean = false,
    val cancelling: Boolean = false,
    val cleanupPending: Boolean = false,
) {
    override fun toString(): String = "AccountSetupState(redacted)"

    fun account(existing: UtMailAccount?): UtMailAccount = UtMailAccount(
        existing?.id ?: AccountIdFactory.create(),
        text(AccountField.NAME),
        text(AccountField.ADDRESS),
        server("imap", AccountField.IMAP_HOST, AccountField.IMAP_PORT, AccountField.IMAP_USER, imapStartTls)
            .copy(authenticationType = existing?.incoming?.authenticationType ?: AuthType.PLAIN),
        server("smtp", AccountField.SMTP_HOST, AccountField.SMTP_PORT, AccountField.SMTP_USER, smtpStartTls)
            .copy(authenticationType = existing?.outgoing?.authenticationType ?: AuthType.PLAIN),
        existing?.credentialKey ?: UUID.randomUUID().toString(),
        existing?.oauthProviderId,
    )

    fun selectProvider(id: String?): AccountSetupState {
        val available = id == null || providers.any { it.id == id }
        if (!editing || editingId != null || !available) return this
        val cleared = AccountField.entries.associateWith { TextFieldValue(it.initial) } + fields.filterKeys {
            it == AccountField.NAME || it == AccountField.ADDRESS
        }
        return copy(providerId = id, fields = cleared, message = null)
    }

    fun changeField(field: AccountField, value: TextFieldValue): AccountSetupState =
        if (field in visibleFields) copy(fields = fields + (field to value)) else this

    val providerAvailable: Boolean get() = providerId == null || providers.any { it.id == providerId }

    val visibleFields: List<AccountField>
        get() = if (providerId == null) AccountField.entries else listOf(AccountField.NAME, AccountField.ADDRESS)

    fun text(field: AccountField): String = fields.getValue(field).text

    private fun server(type: String, host: AccountField, port: AccountField, user: AccountField, startTls: Boolean) =
        ServerSettings(
            type,
            text(host).trim(),
            text(port).toInt(),
            if (startTls) ConnectionSecurity.STARTTLS_REQUIRED else ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            text(user),
            null,
            null,
        )
}

internal sealed interface AccountSetupEvent {
    data class SelectProvider(val id: String?) : AccountSetupEvent
    data object New : AccountSetupEvent
    data class Edit(val id: AccountId) : AccountSetupEvent
    data class Change(val field: AccountField, val value: TextFieldValue) : AccountSetupEvent {
        override fun toString(): String = "AccountSetupEvent.Change(redacted)"
    }
    data class KeyboardEdit(val field: AccountField, val edit: MaliitTextEdit) : AccountSetupEvent {
        override fun toString(): String = "AccountSetupEvent.KeyboardEdit(redacted)"
    }
    data class StartTls(val incoming: Boolean, val enabled: Boolean) : AccountSetupEvent
    data object Save : AccountSetupEvent
    data object Cancel : AccountSetupEvent
    data class RequestRemoval(val id: AccountId) : AccountSetupEvent
    data object ConfirmRemoval : AccountSetupEvent
    data object RetryCredentialCleanup : AccountSetupEvent
    data object CancelRemoval : AccountSetupEvent
}
