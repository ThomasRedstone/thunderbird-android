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
    val hasUncertainAttempt: Boolean = false,
    val cc: String = "",
    val bcc: String = "",
    val inReplyTo: String = "",
    val references: String = "",
) {
    override fun toString(): String = "OutgoingMail(id=$id, state=$state)"
}

/** One application owner. All access is account-scoped and blocking; callers must use IO. */
@Suppress("TooManyFunctions", "MagicNumber") // SQL projections/state transitions form one persistence contract.
internal class OutgoingMailStore(private val database: MailDatabase) {
    fun create(account: AccountId, seed: ResponseSeed = ResponseSeed()): OutgoingMail {
        validate(listOf(seed.to, seed.cc, seed.subject, seed.inReplyTo, seed.references), seed.body)
        val id = UUID.randomUUID().toString()
        database.execute(
            """INSERT INTO outgoing(id,account,recipients,subject,body,message_id,state,cc,in_reply_to,thread_references)
                VALUES(?,?,?,?,?,?,'DRAFT',?,?,?)""",
            id, account.toString(), seed.to, seed.subject, seed.body, "<$id@mail.invalid>", seed.cc,
            seed.inReplyTo, seed.references,
        )
        return get(account, id)
    }

    fun list(account: AccountId): List<OutgoingMail> = database.query(
        "SELECT id,recipients,subject,body,message_id,state,uncertain_attempt,cc,bcc,in_reply_to,thread_references " +
            "FROM outgoing " +
            "WHERE account=? ORDER BY rowid DESC",
        account.toString(),
    ) {
        OutgoingMail(
            getText(0),
            getText(1),
            getText(2),
            getText(3),
            getText(4),
            OutgoingState.valueOf(getText(5)),
            getLong(6) != 0L,
            getText(7),
            getText(8),
            getText(9),
            getText(10),
        )
    }

    fun get(account: AccountId, id: String): OutgoingMail = list(account).single { it.id == id }

    fun save(
        account: AccountId,
        id: String,
        recipients: String,
        subject: String,
        body: String,
        cc: String = "",
        bcc: String = "",
    ): OutgoingMail =
        database.transaction {
            check(get(account, id).state == OutgoingState.DRAFT) { "Only drafts can be edited" }
            val headers = listOf(recipients, cc, bcc, subject)
            validate(headers, body)
            database.execute(
                "UPDATE outgoing SET recipients=?,subject=?,body=?,cc=?,bcc=? WHERE account=? AND id=?",
                recipients,
                subject,
                body,
                cc,
                bcc,
                account.toString(),
                id,
            )
            get(account, id)
        }

    fun queue(account: UtMailAccount, id: String): OutgoingMail = database.transaction {
        val draft = get(account.id, id)
        check(draft.state == OutgoingState.DRAFT)
        val recipients = mapOf("To" to draft.recipients, "Cc" to draft.cc, "Bcc" to draft.bcc)
            .mapValues { (_, text) ->
                Address.parse(text).also { addresses ->
                    require(text.isBlank() || addresses.isNotEmpty()) { "Valid recipients are required" }
                    require(addresses.all { '@' in it.address && it.hostname?.isNotBlank() == true }) {
                        "Valid recipients are required"
                    }
                }
            }
        require(recipients.values.any { it.isNotEmpty() }) { "At least one recipient is required" }
        val message = MimeMessage().apply {
            setFrom(Address(account.address))
            recipients.forEach { (header, addresses) ->
                if (addresses.isNotEmpty()) setHeader(header, addresses.joinToString(", ") { it.toEncodedString() })
            }
            if (draft.inReplyTo.isNotEmpty()) setHeader("In-Reply-To", draft.inReplyTo)
            if (draft.references.isNotEmpty()) setHeader("References", draft.references)
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
    fun claim(
        account: AccountId,
        id: String,
        acknowledgeDuplicateRisk: Boolean = false,
    ): ByteArray? = database.transaction {
        val current = get(account, id)
        if (current.state != OutgoingState.QUEUED) return@transaction null
        if (current.hasUncertainAttempt && !acknowledgeDuplicateRisk) return@transaction null
        val mime = database.query(
            "SELECT mime FROM outgoing WHERE account=? AND id=?",
            account.toString(),
            id,
        ) { getBlob(0) }.single()
        database.execute("UPDATE outgoing SET state='SENDING' WHERE account=? AND id=?", account.toString(), id)
        mime
    }

    fun finish(account: AccountId, id: String, outcome: OutgoingState): OutgoingState = database.transaction {
        require(outcome in setOf(OutgoingState.SENT, OutgoingState.FAILED, OutgoingState.UNCERTAIN))
        val current = get(account, id)
        check(current.state == OutgoingState.SENDING)
        val effective = if (outcome == OutgoingState.FAILED &&
            current.hasUncertainAttempt
        ) {
            OutgoingState.UNCERTAIN
        } else {
            outcome
        }
        val risk = current.hasUncertainAttempt || effective == OutgoingState.UNCERTAIN
        database.execute(
            "UPDATE outgoing SET state=?,uncertain_attempt=? WHERE account=? AND id=?",
            effective.name,
            if (risk) 1 else 0,
            account.toString(),
            id,
        )
        effective
    }

    fun retry(account: AccountId, id: String, acknowledgeDuplicateRisk: Boolean = false) = database.transaction {
        val current = get(account, id)
        check(current.state in setOf(OutgoingState.FAILED, OutgoingState.UNCERTAIN, OutgoingState.QUEUED))
        check(!current.hasUncertainAttempt || acknowledgeDuplicateRisk) {
            "An earlier uncertain delivery requires an explicit duplicate-risk decision"
        }
        check(current.state != OutgoingState.UNCERTAIN || acknowledgeDuplicateRisk)
        database.execute("UPDATE outgoing SET state='QUEUED' WHERE account=? AND id=?", account.toString(), id)
    }

    fun cancelQueued(account: AccountId, id: String) = database.transaction {
        val current = get(account, id)
        if (current.state == OutgoingState.QUEUED && current.hasUncertainAttempt) {
            database.execute("UPDATE outgoing SET state='UNCERTAIN' WHERE account=? AND id=?", account.toString(), id)
        }
    }

    fun delete(account: AccountId, id: String) = database.transaction {
        check(get(account, id).state != OutgoingState.SENDING)
        database.execute("DELETE FROM outgoing WHERE account=? AND id=?", account.toString(), id)
    }

    private fun validate(headers: List<String>, body: String) {
        require(headers.all { it.length <= MAX_HEADER_LENGTH })
        require(body.length <= MAX_BODY_LENGTH)
        require(headers.all { header -> header.none { it in "\r\n" } }) { "Invalid header input" }
    }

    private companion object {
        const val MAX_HEADER_LENGTH = 8192
        const val MAX_BODY_LENGTH = 2 * 1024 * 1024
    }
}
