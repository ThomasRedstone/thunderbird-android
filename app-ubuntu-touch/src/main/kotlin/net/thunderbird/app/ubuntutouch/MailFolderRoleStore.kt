package net.thunderbird.app.ubuntutouch

internal enum class MailFolderRole(val title: String) { ARCHIVE("Archive"), TRASH("Trash") }

/** Explicit folder mappings. A connection change or server folder removal invalidates the old choice. */
internal class MailFolderRoleStore(private val database: MailDatabase) {
    fun read(account: UtMailAccount): Map<MailFolderRole, String> = MailFolderRole.entries.mapNotNull { role ->
        val matches = database.query(
            """SELECT f.server_id FROM folders f JOIN metadata m ON m.folder=f.id AND m.account=f.account
                WHERE f.account=? AND m.name=? AND m.text_value=?""",
            account.id.toString(),
            role.key(),
            mailCacheBinding(account),
        ) { getText(0) }
        check(matches.size <= 1) { "Conflicting folder mappings" }
        matches.singleOrNull()?.let { role to it }
    }.toMap()

    fun configure(account: UtMailAccount, role: MailFolderRole, target: String?) = database.transaction {
        check(AccountSettingsStorage(database).all().singleOrNull { it.id == account.id } == account)
        val folder = target?.let { PersistentBackendStorage(database, account.id.toString()).getFolder(it) }
        database.execute("DELETE FROM metadata WHERE account=? AND name=?", account.id.toString(), role.key())
        folder?.setFolderExtraString(role.setting(), mailCacheBinding(account))
    }
}

private fun MailFolderRole.setting(): String = "ut-mail-role-$name"
private fun MailFolderRole.key(): String = "extra:${setting()}"
