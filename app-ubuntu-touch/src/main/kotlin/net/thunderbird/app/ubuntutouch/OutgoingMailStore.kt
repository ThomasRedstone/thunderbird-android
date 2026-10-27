package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.TextBody
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.UUID
import net.thunderbird.feature.account.AccountId

internal enum class OutgoingState { DRAFT, QUEUED, SENDING, SENT, FAILED, UNCERTAIN }

internal data class OutgoingMail(
    val id: String,
    val recipients: String,
    val subject: String,
    val body: String,
    val messageId: String,
    val state: OutgoingState,
) {
    override fun toString(): String = "OutgoingMail(id=$id, state=$state)"
}

/** One application owner. All access is account-scoped and blocking; callers must use IO. */
@Suppress("TooManyFunctions", "MagicNumber") // SQL projections/state transitions form one persistence contract.
internal class OutgoingMailStore(private val database: MailDatabase) {
    fun create(account: AccountId): OutgoingMail {
        val id = UUID.randomUUID().toString()
        database.execute(
            """INSERT INTO outgoing(id,account,recipients,subject,body,message_id,state)
                VALUES(?,?,'','','',?,'DRAFT')""",
            id,
            account.toString(),
            "<$id@mail.invalid>",
        )
        return get(account, id)
    }

    fun list(account: AccountId): List<OutgoingMail> = database.query(
        "SELECT id,recipients,subject,body,message_id,state FROM outgoing WHERE account=? ORDER BY rowid DESC",
        account.toString(),
    ) { OutgoingMail(getText(0), getText(1), getText(2), getText(3), getText(4), OutgoingState.valueOf(getText(5))) }

    fun get(account: AccountId, id: String): OutgoingMail = list(account).single { it.id == id }

    fun save(account: AccountId, id: String, recipients: String, subject: String, body: String): OutgoingMail =
        database.transaction {
            check(get(account, id).state == OutgoingState.DRAFT) { "Only drafts can be edited" }
            require(recipients.length <= MAX_HEADER_LENGTH && subject.length <= MAX_HEADER_LENGTH)
            require(body.length <= MAX_BODY_LENGTH)
            require(recipients.none { it in "\r\n" } && subject.none { it in "\r\n" }) { "Invalid header input" }
            database.execute(
                "UPDATE outgoing SET recipients=?,subject=?,body=? WHERE account=? AND id=?",
                recipients,
                subject,
                body,
                account.toString(),
                id,
            )
            get(account, id)
        }

    fun queue(account: UtMailAccount, id: String): OutgoingMail = database.transaction {
        val draft = get(account.id, id)
        check(draft.state == OutgoingState.DRAFT)
        val recipients = Address.parse(draft.recipients)
        require(recipients.isNotEmpty() && recipients.all { '@' in it.address && it.hostname?.isNotBlank() == true }) {
            "Valid recipients are required"
        }
        val message = MimeMessage().apply {
            setFrom(Address(account.address))
            setHeader("To", recipients.joinToString(", ") { it.toEncodedString() })
            subject = draft.subject
            messageId = draft.messageId
            setSentDate(Date(), false)
            setHeader("MIME-Version", "1.0")
            setHeader("Content-Type", "text/plain; charset=utf-8")
            body = TextBody(draft.body)
            setEncoding("quoted-printable")
        }
        val raw = ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()
        database.execute(
            "UPDATE outgoing SET state='QUEUED',mime=? WHERE account=? AND id=?",
            raw,
            account.id.toString(),
            id,
        )
        get(account.id, id)
    }

    /** Atomically freeze one attempt. A second caller cannot claim a SENDING/SENT/UNCERTAIN row. */
    fun claim(account: AccountId, id: String): ByteArray? = database.transaction {
        if (get(account, id).state != OutgoingState.QUEUED) return@transaction null
        val mime = database.query(
            "SELECT mime FROM outgoing WHERE account=? AND id=?",
            account.toString(),
            id,
        ) { getBlob(0) }.single()
        database.execute("UPDATE outgoing SET state='SENDING' WHERE account=? AND id=?", account.toString(), id)
        mime
    }

    fun finish(account: AccountId, id: String, outcome: OutgoingState) = database.transaction {
        require(outcome in setOf(OutgoingState.SENT, OutgoingState.FAILED, OutgoingState.UNCERTAIN))
        check(get(account, id).state == OutgoingState.SENDING)
        database.execute("UPDATE outgoing SET state=? WHERE account=? AND id=?", outcome.name, account.toString(), id)
    }

    fun retry(account: AccountId, id: String, acknowledgeDuplicateRisk: Boolean = false) = database.transaction {
        val current = get(account, id).state
        check(current == OutgoingState.FAILED || (current == OutgoingState.UNCERTAIN && acknowledgeDuplicateRisk)) {
            "An uncertain delivery requires an explicit duplicate-risk decision"
        }
        database.execute("UPDATE outgoing SET state='QUEUED' WHERE account=? AND id=?", account.toString(), id)
    }

    fun delete(account: AccountId, id: String) = database.transaction {
        check(get(account, id).state != OutgoingState.SENDING)
        database.execute("DELETE FROM outgoing WHERE account=? AND id=?", account.toString(), id)
    }

    private companion object {
        const val MAX_HEADER_LENGTH = 8192
        const val MAX_BODY_LENGTH = 2 * 1024 * 1024
    }
}
