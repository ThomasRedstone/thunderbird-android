package net.thunderbird.app.ubuntutouch

import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal data class MailMovePayloadInfo(val bytes: Long, val complete: Boolean, val effectiveDate: Long?) {
    override fun toString(): String = "MailMovePayloadInfo(redacted)"
}

/** Immutable first-attempt cached bytes. No FK to live cache folders/messages, so reconciliation cannot erase them. */
internal class MailMovePayloadStore(private val database: MailDatabase) {
    /** Caller owns the same SQL transaction that claims the attempt; a failed copy prevents any wire submission. */
    fun captureWithinClaim(action: MailMove, folder: Long) {
        check(Thread.holdsLock(database))
        if (info(action.account, action.id) != null) return
        database.execute(
            """INSERT INTO move_payloads(id,mime,effective_date,complete,origin_binding)
                SELECT ?,m.mime,m.effective_date,
                    EXISTS(SELECT 1 FROM flags WHERE folder=m.folder AND uid=m.uid AND flag='X_DOWNLOADED_FULL'),
                    m.origin_binding FROM messages m JOIN folders f ON f.id=m.folder
                WHERE f.account=? AND f.id=? AND m.uid=? AND m.origin_binding=?""",
            action.id,
            action.account.toString(),
            folder,
            action.uid,
            action.binding,
        )
        check(info(action.account, action.id) != null) { "Unable to retain cached move payload" }
        database.execute(
            """INSERT INTO move_payload_flags(id,flag) SELECT ?,flag FROM flags WHERE folder=? AND uid=?""",
            action.id,
            folder,
            action.uid,
        )
    }

    fun info(account: AccountId, id: String): MailMovePayloadInfo? = database.query(
        """SELECT length(p.mime),p.complete,p.effective_date FROM move_payloads p JOIN mail_moves a ON a.id=p.id
            WHERE a.account=? AND a.id=?""",
        account.toString(),
        id,
    ) {
        MailMovePayloadInfo(getLong(0), getLong(1) != 0L, if (isNull(2)) null else getLong(2))
    }.singleOrNull()

    fun flags(account: AccountId, id: String): Set<Flag> = database.query(
        """SELECT f.flag FROM move_payload_flags f JOIN mail_moves a ON a.id=f.id WHERE a.account=? AND a.id=?""",
        account.toString(),
        id,
    ) { Flag.valueOf(getText(0)) }.toSet()

    fun page(account: AccountId, id: String, offset: Long): MailSourcePage {
        require(offset >= 0 && offset % SOURCE_PAGE_BYTES == 0L && offset < Long.MAX_VALUE)
        return database.query(
            """SELECT substr(p.mime,?,?),length(p.mime),p.complete FROM move_payloads p JOIN mail_moves a ON a.id=p.id
                WHERE a.account=? AND a.id=? AND (?<length(p.mime) OR (?=0 AND length(p.mime)=0))""",
            offset + 1,
            SOURCE_PAGE_BYTES,
            account.toString(),
            id,
            offset,
            offset,
        ) { MailSourcePage(offset, getLong(1), getLong(2) != 0L, getBlob(0)) }.singleOrNull()
            ?: error("Retained cached source page unavailable")
    }

    /** Automatic release is allowed only after exact bytes and provenance exist in the intended destination cache. */
    fun releaseAfterDestinationCache(expected: MailMove, targetUid: String) = database.transaction {
        require(expected.state == MailMoveState.COMPLETE)
        check(MailMoveStore(database).get(expected.account, expected.id) == expected)
        if (info(expected.account, expected.id) == null) return@transaction
        val account = AccountSettingsStorage(database).all().single { it.id == expected.account }
        check(mailCacheBinding(account) == expected.binding)
        val verified = database.query(
            """SELECT 1 FROM move_payloads p JOIN folders f ON f.account=? AND f.server_id=?
                JOIN messages m ON m.folder=f.id AND m.uid=?
                JOIN metadata v ON v.account=f.account AND v.folder=f.id AND v.name='extra:imapUidValidity'
                WHERE p.id=? AND p.origin_binding=? AND m.origin_binding=p.origin_binding AND m.mime=p.mime
                    AND v.number_value=? AND (p.complete=0 OR EXISTS(
                        SELECT 1 FROM flags WHERE folder=m.folder AND uid=m.uid AND flag='X_DOWNLOADED_FULL'))""",
            expected.account.toString(),
            expected.target,
            targetUid,
            expected.id,
            expected.binding,
            expected.targetValidity,
        ) { getLong(0) }.singleOrNull()
        check(verified == 1L) { "Destination cache is unverified; retained copy kept" }
        database.execute("DELETE FROM move_payloads WHERE id=?", expected.id)
    }
}
