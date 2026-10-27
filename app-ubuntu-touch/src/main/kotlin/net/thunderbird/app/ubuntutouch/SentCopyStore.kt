package net.thunderbird.app.ubuntutouch

import net.thunderbird.feature.account.AccountId

internal enum class SentCopyState { WAITING, WORKING, SAVED, UNKNOWN }
internal data class SentCopy(
    val folder: String,
    val state: SentCopyState,
    val uid: String?,
    val hasUncertainAttempt: Boolean = false,
) {
    override fun toString(): String = "SentCopy(state=$state)"
}
internal data class SentCopyClaim(
    val folder: String,
    val mime: ByteArray,
    val allowAppend: Boolean,
    val messageId: String,
) {
    override fun toString(): String = "SentCopyClaim(redacted)"
}

/** Copy state is independent from SMTP delivery. Only acknowledged SENT rows can be filed. */
@Suppress("MagicNumber") // Fixed SQL projection indices.
internal class SentCopyStore(private val database: MailDatabase) {
    fun folders(account: AccountId): List<MailFolder> = database.query(
        "SELECT server_id,name FROM folders WHERE account=? ORDER BY name",
        account.toString(),
    ) { MailFolder(getText(0), getText(1)) }

    fun target(account: AccountId): String? {
        val configured = database.query("SELECT folder FROM sent_settings WHERE account=?", account.toString()) {
            if (isNull(0)) null else getText(0)
        }
        if (configured.isNotEmpty()) return configured.single()
        return database.query("SELECT server_id FROM folders WHERE account=? AND type='SENT'", account.toString()) {
            getText(0)
        }.singleOrNull()
    }

    fun configure(account: AccountId, folder: String?) {
        require(folder == null || folders(account).any { it.id == folder }) { "Choose a cached folder" }
        database.execute("INSERT OR REPLACE INTO sent_settings(account,folder) VALUES(?,?)", account.toString(), folder)
    }

    fun status(account: AccountId, id: String): SentCopy? = database.query(
        "SELECT folder,state,uid,uncertain_attempt FROM sent_copies WHERE account=? AND outgoing=?",
        account.toString(),
        id,
    ) {
        SentCopy(getText(0), SentCopyState.valueOf(getText(1)), if (isNull(2)) null else getText(2), getLong(3) != 0L)
    }.singleOrNull()

    fun claim(account: AccountId, id: String, acknowledge: Boolean): SentCopyClaim? = database.transaction {
        val outgoing = OutgoingMailStore(database).get(account, id)
        check(outgoing.state == OutgoingState.SENT) {
            "SMTP acceptance is required"
        }
        if (status(account, id) == null) {
            val folder = target(account) ?: return@transaction null
            database.execute(
                "INSERT INTO sent_copies(outgoing,account,folder,state) VALUES(?,?,?,'WAITING')",
                id,
                account.toString(),
                folder,
            )
        }
        val copy = checkNotNull(status(account, id))
        if (copy.state in setOf(SentCopyState.SAVED, SentCopyState.WORKING)) return@transaction null
        val raw = database.query("SELECT mime FROM outgoing WHERE account=? AND id=?", account.toString(), id) {
            getBlob(0)
        }.single()
        database.execute(
            "UPDATE sent_copies SET state='WORKING' WHERE account=? AND outgoing=?",
            account.toString(),
            id,
        )
        SentCopyClaim(copy.folder, raw, copy.state != SentCopyState.UNKNOWN || acknowledge, outgoing.messageId)
    }

    fun finish(account: AccountId, id: String, result: SentCopyResult) = database.transaction {
        val current = checkNotNull(status(account, id))
        check(current.state == SentCopyState.WORKING)
        database.execute(
            "UPDATE sent_copies SET state=?,uid=?,uncertain_attempt=? WHERE account=? AND outgoing=?",
            if (result is SentCopyResult.Saved) "SAVED" else "UNKNOWN",
            (result as? SentCopyResult.Saved)?.uid,
            if (current.hasUncertainAttempt || result == SentCopyResult.Missing) 1 else 0,
            account.toString(),
            id,
        )
    }
}
