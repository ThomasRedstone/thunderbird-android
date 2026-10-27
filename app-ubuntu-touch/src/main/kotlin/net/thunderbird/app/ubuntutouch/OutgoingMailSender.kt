package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.transport.smtp.NegativeSmtpReplyException
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountId

internal fun interface OutgoingTransport {
    fun send(account: AccountId, mime: ByteArray)
}

internal class SessionOutgoingTransport(private val sessions: ForegroundMailSessionFactory) : OutgoingTransport {
    override fun send(account: AccountId, mime: ByteArray) {
        sessions.open(account).use { it.send(MimeMessage.parseMimeMessage(mime.inputStream(), true)) }
    }
}

/** No background loop or implicit retries. SMTP acceptance and Sent-folder filing are separate gates. */
internal class OutgoingMailSender(private val store: OutgoingMailStore, private val transport: OutgoingTransport) {
    fun send(account: AccountId, id: String): OutgoingState {
        val raw = store.claim(account, id) ?: return store.get(account, id).state
        val outcome = try {
            transport.send(account, raw)
            OutgoingState.SENT
        } catch (_: NegativeSmtpReplyException) {
            OutgoingState.FAILED
        } catch (exception: MessagingException) {
            if (exception.isPermanentFailure) OutgoingState.UNCERTAIN else OutgoingState.FAILED
        } catch (_: Exception) {
            // Unclassified failure may occur after DATA acceptance. Never assume it is safe to resend.
            OutgoingState.UNCERTAIN
        }
        store.finish(account, id, outcome)
        return outcome
    }
}
