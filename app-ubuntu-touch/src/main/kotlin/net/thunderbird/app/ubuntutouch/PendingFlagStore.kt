package net.thunderbird.app.ubuntutouch

import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal data class PendingFlag(
    val id: Long,
    val folder: String,
    val uid: String,
    val flag: Flag,
    val enabled: Boolean,
    val uidValidity: Long,
)

/** Idempotent flag assignments survive offline use; each replacement has a new acknowledgement identity. */
@Suppress("MagicNumber") // Fixed SQL projection indices.
internal class PendingFlagStore(private val database: MailDatabase) {
    fun enqueue(account: AccountId, folder: String, uid: String, flag: Flag, enabled: Boolean) = database.transaction {
        require(flag in setOf(Flag.SEEN, Flag.FLAGGED))
        val cached = PersistentBackendStorage(database, account.toString()).getFolder(folder)
        check(cached.isMessagePresent(uid)) { "Message absent from cache" }
        val validity =
            checkNotNull(cached.getFolderExtraNumber("imapUidValidity")) { "Sync this folder before editing" }
        database.execute(
            """INSERT OR REPLACE INTO pending_flags(account,folder,uid,flag,enabled,uid_validity)
                VALUES(?,?,?,?,?,?)""",
            account.toString(),
            cached.id,
            uid,
            flag.name,
            if (enabled) 1 else 0,
            validity,
        )
    }

    fun list(account: AccountId): List<PendingFlag> = database.query(
        """SELECT p.id,f.server_id,p.uid,p.flag,p.enabled,p.uid_validity FROM pending_flags p
            JOIN folders f ON f.id=p.folder AND f.account=p.account WHERE p.account=? ORDER BY p.id""",
        account.toString(),
    ) {
        PendingFlag(getLong(0), getText(1), getText(2), Flag.valueOf(getText(3)), getLong(4) != 0L, getLong(5))
    }

    fun acknowledge(account: AccountId, action: PendingFlag) = database.transaction {
        if (list(account).none { it.id == action.id }) return@transaction
        val cached = PersistentBackendStorage(database, account.toString()).getFolder(action.folder)
        if (cached.isMessagePresent(action.uid) &&
            cached.getFolderExtraNumber("imapUidValidity") == action.uidValidity
        ) {
            if (action.enabled) {
                database.execute(
                    "INSERT OR IGNORE INTO flags(folder,uid,flag) VALUES(?,?,?)",
                    cached.id,
                    action.uid,
                    action.flag.name,
                )
            } else {
                database.execute(
                    "DELETE FROM flags WHERE folder=? AND uid=? AND flag=?",
                    cached.id,
                    action.uid,
                    action.flag.name,
                )
            }
        }
        database.execute("DELETE FROM pending_flags WHERE account=? AND id=?", account.toString(), action.id)
    }

    fun discard(account: AccountId) =
        database.execute("DELETE FROM pending_flags WHERE account=?", account.toString())

    fun overlay(account: AccountId, folder: String, uid: String, flags: Set<Flag>): Set<Flag> {
        val validity = PersistentBackendStorage(database, account.toString()).getFolder(folder)
            .getFolderExtraNumber("imapUidValidity")
        return flags.toMutableSet().apply {
            list(account).filter { it.folder == folder && it.uid == uid && it.uidValidity == validity }.forEach {
                if (it.enabled) add(it.flag) else remove(it.flag)
            }
        }
    }
}
