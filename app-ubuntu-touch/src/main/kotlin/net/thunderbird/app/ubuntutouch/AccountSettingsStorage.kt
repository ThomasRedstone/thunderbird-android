package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory

@Suppress("MagicNumber") // SQL column indices match the adjacent explicit SELECT projections.
internal class AccountSettingsStorage(private val database: MailDatabase) {
    fun all(): List<UtMailAccount> = database.query(
        "SELECT id,display_name,address,credential_key,oauth_provider FROM accounts ORDER BY position,id",
    ) {
        listOf(getText(0), getText(1), getText(2), getText(3)) to if (isNull(4)) null else getText(4)
    }.map { (row, provider) ->
        UtMailAccount(
            AccountIdFactory.of(row[0]),
            row[1],
            row[2],
            server(row[0], "imap"),
            server(row[0], "smtp"),
            row[3],
            provider,
        )
    }

    fun insert(account: UtMailAccount) = database.transaction {
        database.execute(
            """INSERT INTO accounts(id,display_name,address,credential_key,position,oauth_provider)
                VALUES(?,?,?,?,(SELECT COALESCE(MAX(position),-1)+1 FROM accounts),?)""",
            account.id.toString(),
            account.displayName,
            account.address,
            account.credentialKey,
            account.oauthProviderId,
        )
        saveServers(account)
        database.execute("DELETE FROM credential_cleanup WHERE key=?", account.credentialKey)
    }

    fun update(account: UtMailAccount) = database.transaction {
        database.execute(
            "UPDATE accounts SET display_name=?,address=?,credential_key=?,oauth_provider=? WHERE id=?",
            account.displayName,
            account.address,
            account.credentialKey,
            account.oauthProviderId,
            account.id.toString(),
        )
        saveServers(account)
        database.execute("DELETE FROM credential_cleanup WHERE key=?", account.credentialKey)
    }

    fun move(ids: List<AccountId>) = database.transaction {
        ids.forEachIndexed { position, id ->
            database.execute("UPDATE accounts SET position=? WHERE id=?", position, id.toString())
        }
    }

    fun delete(id: AccountId) = database.transaction {
        val account = id.toString()
        database.execute("DELETE FROM metadata WHERE account=?", account)
        database.execute("DELETE FROM folders WHERE account=?", account)
        database.execute("DELETE FROM accounts WHERE id=?", account)
    }

    fun scheduleCredentialCleanup(key: String) = database.execute(
        "INSERT OR IGNORE INTO credential_cleanup(key) VALUES(?)",
        key,
    )

    fun unusedCredentialKeys(): List<String> = database.query(
        "SELECT key FROM credential_cleanup WHERE key NOT IN (SELECT credential_key FROM accounts)",
    ) { getText(0) }

    fun completeCredentialCleanup(key: String) = database.execute("DELETE FROM credential_cleanup WHERE key=?", key)

    private fun server(account: String, kind: String): ServerSettings = database.query(
        "SELECT host,port,security,auth,username FROM servers WHERE account=? AND kind=?",
        account,
        kind,
    ) {
        ServerSettings(
            kind,
            getText(0),
            getLong(1).toInt(),
            ConnectionSecurity.valueOf(getText(2)),
            AuthType.valueOf(getText(3)),
            getText(4),
            null,
            null,
        )
    }.single()

    private fun saveServers(account: UtMailAccount) {
        listOf(account.incoming, account.outgoing).forEach { settings ->
            database.execute(
                """INSERT INTO servers(account,kind,host,port,security,auth,username) VALUES(?,?,?,?,?,?,?)
                    ON CONFLICT(account,kind) DO UPDATE SET host=excluded.host,port=excluded.port,
                    security=excluded.security,auth=excluded.auth,username=excluded.username""",
                account.id.toString(),
                settings.type,
                settings.host,
                settings.port,
                settings.connectionSecurity.name,
                settings.authenticationType.name,
                settings.username,
            )
        }
    }
}
