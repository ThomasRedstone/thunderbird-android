package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import java.net.URI
import java.util.Locale
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal enum class ResponseMode { REPLY, REPLY_ALL, FORWARD }

internal data class ResponseSeed(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String = "",
    val references: String = "",
) {
    override fun toString(): String = "ResponseSeed(redacted)"
}

/** Creates a saved local draft only. Android's legacy recipient parser depends on LegacyAccount. */
internal class ResponseDraftFactory(private val database: MailDatabase) {
    fun create(account: AccountId, folder: String, uid: String, mode: ResponseMode): String {
        val accounts = AccountSettingsStorage(database).all()
        check(accounts.any { it.id == account }) { "Account absent" }
        val cached = PersistentBackendStorage(database, account.toString()).getFolder(folder)
        check(Flag.X_DOWNLOADED_FULL in cached.getMessageFlags(uid)) { "Download the complete message first" }
        val message = checkNotNull(cached.readMessage(uid))
        val text = checkNotNull(plainTextBody(message)) { "A plain-text body is required" }
        val seed = seed(message, text, accounts.map { it.address }, mode)
        return OutgoingMailStore(database).create(account, seed).id
    }

    internal fun seed(message: Message, text: String, ownAddresses: List<String>, mode: ResponseMode): ResponseSeed {
        val subject = message.subject.orEmpty().replace(Regex("[\\r\\n]+"), " ")
        if (mode == ResponseMode.FORWARD) {
            return ResponseSeed(
                subject = prefix(subject, "Fwd"),
                body = forwardedText(message, subject, text),
            )
        }
        val own = ownAddresses.map { it.lowercase(Locale.ROOT) }.toSet()
        val reply = replyTargets(message)
        val primary = reply.filterNot { it.address.lowercase(Locale.ROOT) in own }.ifEmpty {
            message.getRecipients(Message.RecipientType.TO).filterNot { it.address.lowercase(Locale.ROOT) in own }
        }
        val to = if (mode == ResponseMode.REPLY_ALL) {
            primary + message.from + message.getRecipients(Message.RecipientType.TO)
        } else {
            primary
        }
        val seen = own.toMutableSet()
        fun unique(addresses: List<Address>) = addresses.filter { seen.add(it.address.lowercase(Locale.ROOT)) }
        val recipients = unique(to.toList())
        val cc = if (mode == ResponseMode.REPLY_ALL) {
            unique(message.getRecipients(Message.RecipientType.CC).toList())
        } else {
            emptyList()
        }
        val parent = message.messageId.orEmpty().trim().takeIf { MESSAGE_ID.matches(it) }.orEmpty()
        val ancestors = MESSAGE_ID.findAll(message.getHeader("References").joinToString(" "))
            .map { it.value }.filter { it.length <= MAX_ID_LENGTH }.toList()
        val thread = (
            ancestors.filter {
                it != parent
            } + listOfNotNull(parent.takeIf { it.length in 1..MAX_ID_LENGTH })
            ).distinct()
        val references = (thread.take(1) + thread.takeLast(MAX_RECENT_IDS)).distinct().joinToString(" ")
        return ResponseSeed(
            to = recipients.joinToString(", ") { it.toEncodedString() },
            cc = cc.joinToString(", ") { it.toEncodedString() },
            subject = prefix(subject, "Re"),
            body = "\n\n" + text.replace("\r\n", "\n").lineSequence().joinToString("\n") { "> $it" },
            inReplyTo = parent.takeIf { it.length <= MAX_ID_LENGTH }.orEmpty(),
            references = references,
        )
    }

    private fun replyTargets(message: Message): Array<Address> {
        if (message.replyTo.isNotEmpty()) return message.replyTo
        val lists = message.getHeader("List-Post").mapNotNull { header ->
            MAILTO.find(header)?.groupValues?.get(1)?.let { path ->
                runCatching { URI("mailto:$path").schemeSpecificPart }.getOrNull()
                    ?.takeIf { it.length <= MAX_LIST_ADDRESS && it.none { character -> character in "\r\n" } }
                    ?.let { Address.parse(it).firstOrNull { address -> '@' in address.address } }
            }
        }
        return if (lists.isNotEmpty()) lists.toTypedArray() else message.from
    }

    private fun forwardedText(message: Message, subject: String, text: String): String = buildString {
        append("\n\n---------- Forwarded text ----------\n")
        append("From: ${message.from.joinToString { it.toString() }}\n")
        message.sentDate?.let { append("Date: $it\n") }
        append("Subject: $subject\n")
        append("To: ${message.getRecipients(Message.RecipientType.TO).joinToString { it.toString() }}\n")
        val cc = message.getRecipients(Message.RecipientType.CC)
        if (cc.isNotEmpty()) append("Cc: ${cc.joinToString { it.toString() }}\n")
        append("\n$text")
    }

    private fun prefix(subject: String, prefix: String): String =
        if (subject.startsWith("$prefix:", ignoreCase = true)) subject else "$prefix: $subject"

    private companion object {
        val MAILTO = Regex("<mailto:([^<>?\\s]+)(?:\\?[^<>]*)?>", RegexOption.IGNORE_CASE)
        const val MAX_LIST_ADDRESS = 8192
        val MESSAGE_ID = Regex("<[^<>\\s]+@[^<>\\s]+>")
        const val MAX_ID_LENGTH = 512
        const val MAX_RECENT_IDS = 9
    }
}
