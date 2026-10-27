package net.thunderbird.app.ubuntutouch

import net.thunderbird.feature.account.AccountId

/** Plain text only. Existing drafts remain snapshots and are never rewritten after settings change. */
internal data class MailSignature(
    val text: String = "",
    val enabled: Boolean = false,
    val replies: Boolean = true,
    val forwards: Boolean = true,
) {
    init {
        require(text.length <= MAX_SIGNATURE_CHARACTERS)
        require(text.none { it == '\u0000' || (Character.isISOControl(it) && it !in "\r\n\t") })
    }
    override fun toString(): String = "MailSignature(redacted)"

    fun apply(seed: ResponseSeed, mode: ResponseMode? = null): ResponseSeed {
        val include = enabled && text.isNotBlank() && when (mode) {
            ResponseMode.REPLY, ResponseMode.REPLY_ALL -> replies
            ResponseMode.FORWARD -> forwards
            null -> true
        }
        if (!include) return seed
        val signature = "-- \n" + text.replace("\r\n", "\n").replace('\r', '\n')
        val body = if (mode == null) seed.body + "\n\n" + signature else "\n\n" + signature + seed.body
        return seed.copy(body = body)
    }
}

internal class MailSignatureStore(private val database: MailDatabase) {
    fun read(account: AccountId): MailSignature = database.query(
        "SELECT text,enabled,replies,forwards FROM signatures WHERE account=?",
        account.toString(),
    ) {
        MailSignature(getText(0), getLong(1) != 0L, getLong(2) != 0L, getLong(FORWARDS_COLUMN) != 0L)
    }.singleOrNull() ?: MailSignature()

    fun save(account: AccountId, signature: MailSignature) {
        database.execute(
            """INSERT INTO signatures(account,text,enabled,replies,forwards) VALUES(?,?,?,?,?)
                ON CONFLICT(account) DO UPDATE SET text=excluded.text,enabled=excluded.enabled,
                replies=excluded.replies,forwards=excluded.forwards""",
            account.toString(),
            signature.text,
            signature.enabled.asSql(),
            signature.replies.asSql(),
            signature.forwards.asSql(),
        )
    }
}

internal const val MAX_SIGNATURE_CHARACTERS = 16_384

private fun Boolean.asSql(): Int = if (this) 1 else 0
private const val FORWARDS_COLUMN = 3
