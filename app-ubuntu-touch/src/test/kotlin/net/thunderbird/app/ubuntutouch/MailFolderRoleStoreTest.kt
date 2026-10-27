package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailFolderRoleStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `explicit archive and trash choices survive reopen and stay account scoped`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        lateinit var first: UtMailAccount
        lateinit var second: UtMailAccount
        MailDatabase(path).use { database ->
            first = seed(database)
            second = seed(database)
            val testSubject = MailFolderRoleStore(database)
            assertThat(testSubject.read(first)).isEqualTo(emptyMap())
            testSubject.configure(first, MailFolderRole.ARCHIVE, "Archive")
            testSubject.configure(first, MailFolderRole.TRASH, "Trash")
            testSubject.configure(second, MailFolderRole.ARCHIVE, "Trash")
        }
        MailDatabase(path).use { database ->
            val testSubject = MailFolderRoleStore(database)
            assertThat(testSubject.read(first)).isEqualTo(
                mapOf(
                    MailFolderRole.ARCHIVE to "Archive",
                    MailFolderRole.TRASH to "Trash",
                ),
            )
            assertThat(testSubject.read(second)).isEqualTo(mapOf(MailFolderRole.ARCHIVE to "Trash"))
            testSubject.configure(first, MailFolderRole.ARCHIVE, null)
            assertThat(testSubject.read(first)).isEqualTo(mapOf(MailFolderRole.TRASH to "Trash"))
        }
    }

    @Test
    fun `connection mutation invalidates old roles and rejects stale configuration writes`() = fixture { database ->
        val account = seed(database)
        val testSubject = MailFolderRoleStore(database)
        testSubject.configure(account, MailFolderRole.ARCHIVE, "Archive")
        val changed = account.copy(incoming = account.incoming.copy(host = "changed.example.invalid"))
        AccountSettingsStorage(database).update(changed)
        assertThat(testSubject.read(changed)).isEqualTo(emptyMap())
        assertThat(runCatching { testSubject.configure(account, MailFolderRole.TRASH, "Trash") }.isFailure).isTrue()
        testSubject.configure(changed, MailFolderRole.TRASH, "Trash")
        assertThat(testSubject.read(changed)).isEqualTo(mapOf(MailFolderRole.TRASH to "Trash"))
    }

    @Test
    fun `missing destination rolls back and deletion invalidates mappings`() = fixture { database ->
        val account = seed(database)
        val testSubject = MailFolderRoleStore(database)
        testSubject.configure(account, MailFolderRole.ARCHIVE, "Archive")
        assertThat(runCatching { testSubject.configure(account, MailFolderRole.ARCHIVE, "Missing") }.isFailure).isTrue()
        assertThat(testSubject.read(account)).isEqualTo(mapOf(MailFolderRole.ARCHIVE to "Archive"))
        PersistentBackendStorage(database, account.id.toString()).createFolderUpdater().use {
            it.deleteFolders(listOf("Archive"))
        }
        assertThat(testSubject.read(account)).isEqualTo(emptyMap())
        testSubject.configure(account, MailFolderRole.TRASH, "Trash")
        AccountSettingsStorage(database).delete(account.id)
        assertThat(testSubject.read(account)).isEqualTo(emptyMap())
        assertThat(
            database.query("SELECT count(*) FROM metadata WHERE account=?", account.id.toString()) {
                getLong(0)
            }.single(),
        ).isEqualTo(0L)
    }

    private fun fixture(work: (MailDatabase) -> Unit) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use(work)
    }

    private fun seed(database: MailDatabase): UtMailAccount {
        val server = ServerSettings(
            "imap",
            "localhost",
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        val account = UtMailAccount(
            AccountIdFactory.create(),
            "Fixture",
            "fixture@example.invalid",
            server,
            server.copy(type = "smtp", port = 465),
            "fixture-key",
        )
        AccountSettingsStorage(database).insert(account)
        PersistentBackendStorage(database, account.id.toString()).createFolderUpdater().use {
            it.createFolders(
                listOf(
                    FolderInfo("Archive", "Archive", FolderType.REGULAR),
                    FolderInfo("Trash", "Trash", FolderType.REGULAR),
                ),
            )
        }
        return account
    }
}
