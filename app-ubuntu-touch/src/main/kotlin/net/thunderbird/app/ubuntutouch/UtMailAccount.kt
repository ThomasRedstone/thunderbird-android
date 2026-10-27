package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import net.thunderbird.core.common.net.HostNameUtils
import net.thunderbird.feature.account.Account
import net.thunderbird.feature.account.AccountId

private const val MAX_DISPLAY_NAME_LENGTH = 256
private const val MAX_PASSWORD_LENGTH = 4096
private const val MAX_SERVER_PORT = 65535

internal data class UtMailAccount(
    override val id: AccountId,
    val displayName: String,
    val address: String,
    val incoming: ServerSettings,
    val outgoing: ServerSettings,
    val credentialKey: String,
) : Account {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH)
        require(address.isNotBlank() && '@' in address && address.none { it.isWhitespace() || it in "\r\n" })
        require(credentialKey.isNotBlank())
        validateServer(incoming, "imap")
        validateServer(outgoing, "smtp")
    }

    override fun toString(): String = "UtMailAccount(id=$id)"
}

private fun validateServer(settings: ServerSettings, type: String) {
    require(settings.type == type)
    require(HostNameUtils.isLegalHostNameOrIP(settings.host) != null) { "Invalid server hostname" }
    require(settings.port in 1..MAX_SERVER_PORT)
    require(settings.connectionSecurity != ConnectionSecurity.NONE) { "TLS is required" }
    require(settings.authenticationType == AuthType.PLAIN || settings.authenticationType == AuthType.CRAM_MD5)
    require(settings.username.isNotBlank())
    require(settings.password == null && settings.clientCertificateAlias == null && settings.extra.isEmpty()) {
        "Account configuration must not contain secrets or unsupported extensions"
    }
}

internal class AccountPasswords(val incoming: String, val outgoing: String) {
    init {
        listOf(incoming, outgoing).forEach { password ->
            require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH && password.none { it in "\r\n" })
        }
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeUTF(incoming)
            output.writeUTF(outgoing)
        }
    }.toByteArray()

    override fun toString(): String = "AccountPasswords(redacted)"

    companion object {
        fun decode(value: ByteArray): AccountPasswords = DataInputStream(value.inputStream()).use {
            val passwords = AccountPasswords(it.readUTF(), it.readUTF())
            check(it.available() == 0) { "Invalid credential encoding" }
            passwords
        }
    }
}
