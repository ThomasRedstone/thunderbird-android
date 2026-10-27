package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.transport.smtp.NegativeSmtpReplyException
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountId

internal fun interface OutgoingTransport {
    fun send(account: AccountId, mime: ByteArray)
    fun send(account: AccountId, mime: ByteArray, operation: MailOperation?) = send(account, mime)
}

internal class SessionOutgoingTransport(private val sessions: ForegroundMailSessionFactory) : OutgoingTransport {
    override fun send(account: AccountId, mime: ByteArray) = send(account, mime, null)

    override fun send(account: AccountId, mime: ByteArray, operation: MailOperation?) {
        sessions.open(account, operation).use { it.send(MimeMessage.parseMimeMessage(mime.inputStream(), true)) }
    }
}

/** No background loop or implicit retries. SMTP acceptance and Sent-folder filing are separate gates. */
internal class OutgoingMailSender(
    private val store: OutgoingMailStore,
    private val transport: OutgoingTransport,
    private val sentCopies: SentCopyFiler?,
) {
    constructor(store: OutgoingMailStore, transport: OutgoingTransport) : this(store, transport, null)

    fun send(
        account: AccountId,
        id: String,
        operation: MailOperation? = null,
        acknowledgeDuplicateRisk: Boolean = false,
        onAccepted: () -> Unit = {},
    ): OutgoingState {
        if (operation?.cancellationRequested == true) store.cancelQueued(account, id)
        val raw = if (operation?.cancellationRequested ==
            true
        ) {
            null
        } else {
            store.claim(account, id, acknowledgeDuplicateRisk)
        }
        if (raw == null) return store.get(account, id).state
        val outcome = try {
            operation?.ensureActive()
            transport.send(account, raw, operation)
            OutgoingState.SENT
        } catch (_: MailOperationCancelledException) {
            OutgoingState.FAILED
        } catch (_: NegativeSmtpReplyException) {
            OutgoingState.FAILED
        } catch (exception: MessagingException) {
            if (exception.isPermanentFailure) OutgoingState.UNCERTAIN else OutgoingState.FAILED
        } catch (_: Exception) {
            // Unclassified failure may occur after DATA acceptance. Never assume it is safe to resend.
            OutgoingState.UNCERTAIN
        }
        val saved = store.finish(account, id, outcome)
        if (saved == OutgoingState.SENT) {
            try {
                onAccepted()
                sentCopies?.file(account, id, operation)
            } catch (_: Exception) {
                // SMTP is already acknowledged and durable. A copy failure cannot change delivery or resend.
            }
        }
        return saved
    }
}
