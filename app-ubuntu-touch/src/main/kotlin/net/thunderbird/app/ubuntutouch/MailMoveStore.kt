package net.thunderbird.app.ubuntutouch

import java.util.UUID
import net.thunderbird.feature.account.AccountId

internal enum class MailMoveKind(val title: String) { MOVE("Move"), COPY("Copy") }

internal enum class MailMoveState { QUEUED, WORKING, UNKNOWN, COMPLETE }

internal data class MailMove(
    val id: String,
    val account: AccountId,
    val source: String,
    val uid: String,
    val target: String,
    val sourceValidity: Long,
    val targetValidity: Long,
    val state: MailMoveState,
    val attempt: Long,
    val uncertain: Boolean,
    val binding: String,
    val kind: MailMoveKind = MailMoveKind.MOVE,
) {
    override fun toString(): String = "MailMove(redacted)"
}

/** Intent and attempt journal; cache bytes remain untouched. Never infer an uncertain move is safe to replay. */
@Suppress("MagicNumber") // Fixed SQL projection indices.
internal class MailMoveStore(private val database: MailDatabase) {
    fun enqueue(
        key: MailMessageKey,
        target: String,
        kind: MailMoveKind = MailMoveKind.MOVE,
        acknowledgeDuplicateRisk: Boolean = false,
    ): MailMove = database.transaction {
        require(key.folder != target)
        val storage = PersistentBackendStorage(database, key.account.toString())
        val sourceFolder = storage.getFolder(key.folder)
        val targetFolder = storage.getFolder(target)
        check(sourceFolder.isMessagePresent(key.uid))
        check(PendingFlagStore(database).list(key.account).none { it.folder == key.folder && it.uid == key.uid }) {
            "Sync pending message changes before moving"
        }
        val sourceValidity = checkNotNull(sourceFolder.getFolderExtraNumber("imapUidValidity"))
        val targetValidity = checkNotNull(targetFolder.getFolderExtraNumber("imapUidValidity"))
        require(sourceValidity > 0 && targetValidity > 0)
        val currentBinding = binding(key.account)
        checkCompletedFiling(
            key,
            target,
            sourceValidity,
            targetValidity,
            currentBinding,
            kind,
            acknowledgeDuplicateRisk,
        )
        checkSourceBinding(sourceFolder, key.uid, currentBinding)
        check(targetFolder.getFolderExtraString(CACHE_BINDING_KEY) == currentBinding) {
            "Sync the destination before moving"
        }
        val id = UUID.randomUUID().toString()
        database.execute(
            """INSERT INTO mail_moves(id,account,source,uid,target,source_validity,target_validity,state,binding,kind)
                VALUES(?,?,?,?,?,?,?,'QUEUED',?,?)""",
            id,
            key.account.toString(),
            key.folder,
            key.uid,
            target,
            sourceValidity,
            targetValidity,
            currentBinding,
            kind.name,
        )
        get(key.account, id)
    }

    fun list(account: AccountId): List<MailMove> = database.query(
        """SELECT id,source,uid,target,source_validity,target_validity,state,attempt,uncertain,binding,kind
            FROM mail_moves WHERE account=? ORDER BY rowid""",
        account.toString(),
    ) {
        MailMove(
            getText(0), account, getText(1), getText(2), getText(3), getLong(4), getLong(5),
            MailMoveState.valueOf(getText(6)), getLong(7), getLong(8) != 0L, getText(9),
            MailMoveKind.valueOf(getText(10)),
        )
    }

    fun get(account: AccountId, id: String): MailMove = list(account).single { it.id == id }

    /** Called immediately before the engine can submit a move, after its generation/cancellation checks. */
    fun claim(expected: MailMove, acknowledgeDuplicateRisk: Boolean = false): MailMove = database.transaction {
        checkCurrent(expected)
        require(
            expected.state == MailMoveState.QUEUED ||
                (expected.state == MailMoveState.UNKNOWN && acknowledgeDuplicateRisk),
        )
        val storage = PersistentBackendStorage(database, expected.account.toString())
        check(storage.getFolder(expected.source).getFolderExtraNumber("imapUidValidity") == expected.sourceValidity)
        check(storage.getFolder(expected.target).getFolderExtraNumber("imapUidValidity") == expected.targetValidity)
        check(storage.getFolder(expected.source).isMessagePresent(expected.uid))
        check(expected.attempt < Long.MAX_VALUE)
        MailMovePayloadStore(database).captureWithinClaim(expected, storage.getFolder(expected.source).id)
        database.execute(
            "UPDATE mail_moves SET state='WORKING',attempt=attempt+1 WHERE account=? AND id=?",
            expected.account.toString(),
            expected.id,
        )
        get(expected.account, expected.id)
    }

    fun checkCurrent(expected: MailMove) {
        check(get(expected.account, expected.id) == expected)
        check(binding(expected.account) == expected.binding) { "Account connection changed; move retained for review" }
        val storage = PersistentBackendStorage(database, expected.account.toString())
        checkSourceBinding(storage.getFolder(expected.source), expected.uid, expected.binding)
        check(storage.getFolder(expected.target).getFolderExtraString(CACHE_BINDING_KEY) == expected.binding)
    }

    private fun checkSourceBinding(folder: PersistentBackendFolder, uid: String, binding: String) {
        check(folder.messageBinding(uid) == binding) {
            "Verify cached message against the current account before moving"
        }
    }

    private fun binding(id: AccountId): String {
        val account = AccountSettingsStorage(database).all().single { it.id == id }
        return mailCacheBinding(account)
    }

    fun finish(claim: MailMove, confirmed: Boolean): MailMove = database.transaction {
        require(claim.state == MailMoveState.WORKING)
        val current = get(claim.account, claim.id)
        if (current != claim) return@transaction current
        database.execute(
            "UPDATE mail_moves SET state=?,uncertain=? WHERE account=? AND id=?",
            if (confirmed) MailMoveState.COMPLETE.name else MailMoveState.UNKNOWN.name,
            if (confirmed && !current.uncertain) 0 else 1,
            claim.account.toString(),
            claim.id,
        )
        get(claim.account, claim.id)
    }

    /** Only an unsubmitted intent can be cancelled without an unknown-result review. */
    fun cancelQueued(expected: MailMove) = database.transaction {
        require(expected.state == MailMoveState.QUEUED)
        check(get(expected.account, expected.id) == expected)
        database.execute("DELETE FROM mail_moves WHERE account=? AND id=?", expected.account.toString(), expected.id)
    }
}

private fun MailMoveStore.checkCompletedFiling(
    key: MailMessageKey,
    target: String,
    sourceValidity: Long,
    targetValidity: Long,
    binding: String,
    kind: MailMoveKind,
    acknowledgeDuplicateRisk: Boolean,
) {
    val previous = list(key.account).filter {
        it.source == key.folder && it.uid == key.uid && it.sourceValidity == sourceValidity &&
            it.binding == binding && it.state == MailMoveState.COMPLETE
    }
    check(previous.none { it.kind == MailMoveKind.MOVE }) { "Confirmed move cannot be repeated from stale cache" }
    if (kind == MailMoveKind.COPY && previous.any { it.target == target && it.targetValidity == targetValidity }) {
        check(acknowledgeDuplicateRisk) { "Review duplicate copy risk before queueing another copy" }
    }
}
