package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.BackendFolderUpdater
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType

/** Account identity is opaque and independent of user-supplied server names. */
internal class PersistentBackendStorage(
    private val database: MailDatabase,
    private val account: String,
) : BackendStorage {
    init {
        require(account.isNotBlank())
    }

    override fun getFolder(folderServerId: String): PersistentBackendFolder {
        val id = database.query("SELECT id FROM folders WHERE account=? AND server_id=?", account, folderServerId) {
            getLong(0)
        }.single()
        return PersistentBackendFolder(database, account, id)
    }

    override fun getFolderServerIds(): List<String> =
        database.query("SELECT server_id FROM folders WHERE account=? ORDER BY id", account) { getText(0) }

    override fun createFolderUpdater(): BackendFolderUpdater = Updater()
    override fun getExtraString(name: String): String? = metadata.text(name)
    override fun setExtraString(name: String, value: String) = metadata.setText(name, value)
    override fun getExtraNumber(name: String): Long? = metadata.number(name)
    override fun setExtraNumber(name: String, value: Long) = metadata.setNumber(name, value)

    private val metadata = MailMetadata(database, account, -1)

    private inner class Updater : BackendFolderUpdater {
        override fun createFolders(folders: List<FolderInfo>): Set<Long> = database.transaction {
            folders.map { folder ->
                database.execute(
                    "INSERT INTO folders(account,server_id,name,type) VALUES(?,?,?,?)",
                    account,
                    folder.serverId,
                    folder.name,
                    folder.type.name,
                )
                database.query("SELECT last_insert_rowid()") { getLong(0) }.single()
            }.toSet()
        }

        override fun deleteFolders(folderServerIds: List<String>) = database.transaction {
            folderServerIds.forEach { serverId ->
                val folder = getFolder(serverId)
                database.execute("DELETE FROM metadata WHERE account=? AND folder=?", account, folder.id)
                database.execute("DELETE FROM folders WHERE account=? AND server_id=?", account, serverId)
            }
        }

        override fun changeFolder(folderServerId: String, name: String, type: FolderType) {
            getFolder(folderServerId)
            database.execute(
                "UPDATE folders SET name=?,type=? WHERE account=? AND server_id=?",
                name,
                type.name,
                account,
                folderServerId,
            )
        }

        override fun close() = Unit
    }
}

internal class MailMetadata(private val database: MailDatabase, private val account: String, private val folder: Long) {
    fun text(name: String): String? = database.query(
        "SELECT text_value FROM metadata WHERE account=? AND folder=? AND name=?",
        account,
        folder,
        name,
    ) { if (isNull(0)) null else getText(0) }.singleOrNull()

    fun number(name: String): Long? = database.query(
        "SELECT number_value FROM metadata WHERE account=? AND folder=? AND name=?",
        account,
        folder,
        name,
    ) { if (isNull(0)) null else getLong(0) }.singleOrNull()

    fun setText(name: String, value: String?) {
        database.execute(
            """INSERT INTO metadata(account,folder,name,text_value) VALUES(?,?,?,?)
                ON CONFLICT(account,folder,name) DO UPDATE SET text_value=excluded.text_value""",
            account,
            folder,
            name,
            value,
        )
    }

    fun setNumber(name: String, value: Long) {
        database.execute(
            """INSERT INTO metadata(account,folder,name,number_value) VALUES(?,?,?,?)
                ON CONFLICT(account,folder,name) DO UPDATE SET number_value=excluded.number_value""",
            account,
            folder,
            name,
            value,
        )
    }
}
