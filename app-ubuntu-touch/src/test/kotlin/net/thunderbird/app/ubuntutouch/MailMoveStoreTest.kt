package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailMoveStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `schema twelve intents stay unbound after migration and cannot use current connection implicitly`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        lateinit var account: AccountId
        lateinit var id: String
        MailDatabase(path).use { database ->
            account = populate(database)
            id = MailMoveStore(database).enqueue(MailMessageKey(account, "INBOX", "1"), "Archive").id
            database.execute("ALTER TABLE mail_moves DROP COLUMN binding")
            database.execute("PRAGMA user_version=12")
        }
        MailDatabase(path).use { database ->
            val store = MailMoveStore(database)
            val recovered = store.get(account, id)
            assertThat(recovered.binding).isEqualTo("")
            assertThat(runCatching { store.claim(recovered) }.isFailure).isTrue()
            assertThat(recovered.state).isEqualTo(MailMoveState.QUEUED)
            assertThat(database.query("SELECT count(*) FROM messages") { getLong(0) }.single()).isEqualTo(1L)
            store.cancelQueued(recovered)
        }
    }

    @Test
    fun `runner distinguishes preflight failure from submitted uncertainty and never retries implicitly`() = fixture {
            _,
            account,
            store,
        ->
        val queued = store.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
        val preflight = MailMoveRunner(store) { _, _, _ -> error("Sensitive preflight details") }
        assertThat(preflight.run(queued, MailOperation())).isEqualTo(queued)
        var attempts = 0
        val testSubject = MailMoveRunner(store) { _, _, submit ->
            submit()
            attempts++
            error("Sensitive lost reply")
        }
        val unknown = testSubject.run(queued, MailOperation())
        assertThat(unknown.state).isEqualTo(MailMoveState.UNKNOWN)
        assertThat(runCatching { testSubject.run(unknown, MailOperation()) }.isFailure).isTrue()
        assertThat(attempts).isEqualTo(1)
        val retry = MailMoveRunner(store) { _, _, submit ->
            submit()
            attempts++
        }
        val completed = retry.run(unknown, MailOperation(), acknowledgeDuplicateRisk = true)
        assertThat(completed.state).isEqualTo(MailMoveState.COMPLETE)
        assertThat(completed.uncertain).isTrue()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `connection replacement blocks runner before transport even with matching folder generations`() = fixture {
            database,
            account,
            store,
        ->
        val queued = store.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
        val settings = AccountSettingsStorage(database)
        val original = settings.all().single()
        settings.update(original.copy(incoming = original.incoming.copy(host = "other.example.invalid")))
        var calls = 0
        val testSubject = MailMoveRunner(store) { _, _, _ -> calls++ }
        assertThat(runCatching { testSubject.run(queued, MailOperation()) }.isFailure).isTrue()
        assertThat(calls).isEqualTo(0)
        assertThat(store.get(account, queued.id).state).isEqualTo(MailMoveState.QUEUED)
    }

    @Test
    fun `confirmed move cannot be queued again from unchanged source cache`() = fixture { _, account, testSubject ->
        val key = MailMessageKey(account, "INBOX", "1")
        val queued = testSubject.enqueue(key, "Archive")
        testSubject.finish(testSubject.claim(queued), confirmed = true)
        assertThat(runCatching { testSubject.enqueue(key, "Archive") }.isFailure).isTrue()
    }

    @Test
    fun `uncertain moves survive restart and require duplicate risk acknowledgement for another attempt`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        lateinit var claim: MailMove
        lateinit var account: AccountId
        MailDatabase(path).use { database ->
            account = populate(database)
            val testSubject = MailMoveStore(database)
            val queued = testSubject.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
            claim = testSubject.claim(queued)
            assertThat(claim.state).isEqualTo(MailMoveState.WORKING)
            assertThat(testSubject.list(AccountIdFactory.create())).isEqualTo(emptyList())
        }
        MailDatabase(path).use { database ->
            val testSubject = MailMoveStore(database)
            val recovered = testSubject.get(account, claim.id)
            assertThat(recovered.state).isEqualTo(MailMoveState.UNKNOWN)
            assertThat(recovered.uncertain).isTrue()
            assertThat(runCatching { testSubject.claim(recovered) }.isFailure).isTrue()
            val retry = testSubject.claim(recovered, acknowledgeDuplicateRisk = true)
            assertThat(retry.attempt).isEqualTo(2L)
            assertThat(testSubject.finish(claim, confirmed = true)).isEqualTo(retry)
            val complete = testSubject.finish(retry, confirmed = true)
            assertThat(complete.state).isEqualTo(MailMoveState.COMPLETE)
            assertThat(complete.uncertain).isTrue()
            assertThat(database.query("SELECT count(*) FROM messages") { getLong(0) }.single()).isEqualTo(1L)
        }
    }

    @Test
    fun `same source conflicts and mailbox generation changes prevent submission without changing cache`() = fixture {
            database,
            account,
            testSubject,
        ->
        val key = MailMessageKey(account, "INBOX", "1")
        val queued = testSubject.enqueue(key, "Archive")
        assertThat(runCatching { testSubject.enqueue(key, "Archive") }.isFailure).isTrue()
        assertThat(
            runCatching {
                PendingFlagStore(database).enqueue(account, "INBOX", "1", Flag.SEEN, true)
            }.isFailure,
        ).isTrue()
        PersistentBackendStorage(
            database,
            account.toString(),
        ).getFolder("Archive").setFolderExtraNumber("imapUidValidity", 99)
        assertThat(runCatching { testSubject.claim(queued) }.isFailure).isTrue()
        assertThat(testSubject.get(account, queued.id).state).isEqualTo(MailMoveState.QUEUED)
        testSubject.cancelQueued(queued)
        PendingFlagStore(database).enqueue(account, "INBOX", "1", Flag.SEEN, true)
        assertThat(runCatching { testSubject.enqueue(key, "Archive") }.isFailure).isTrue()
        assertThat(database.query("SELECT count(*) FROM messages") { getLong(0) }.single()).isEqualTo(1L)
    }

    @Test
    fun `in flight failure stays unknown and cannot be removed as an unsubmitted intent`() = fixture {
            _,
            account,
            testSubject,
        ->
        val queued = testSubject.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
        val claim = testSubject.claim(queued)
        val unknown = testSubject.finish(claim, confirmed = false)
        assertThat(unknown.state).isEqualTo(MailMoveState.UNKNOWN)
        assertThat(runCatching { testSubject.cancelQueued(unknown) }.isFailure).isTrue()
        assertThat(testSubject.get(account, queued.id)).isEqualTo(unknown)
        assertThat(unknown.toString()).isEqualTo("MailMove(redacted)")
    }

    @Test
    fun `schema eleven upgrades retaining signatures and cached messages`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        lateinit var account: AccountId
        MailDatabase(path).use { database ->
            account = populate(database)
            MailSignatureStore(database).save(account, MailSignature("Fixture", enabled = true))
            database.execute("DROP TABLE mail_moves")
            database.execute("PRAGMA user_version=11")
        }
        MailDatabase(path).use { database ->
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(13L)
            assertThat(MailSignatureStore(database).read(account).text).isEqualTo("Fixture")
            assertThat(database.query("SELECT count(*) FROM messages") { getLong(0) }.single()).isEqualTo(1L)
            assertThat(MailMoveStore(database).list(account)).isEqualTo(emptyList())
        }
    }

    private fun fixture(work: (MailDatabase, AccountId, MailMoveStore) -> Unit) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            work(database, populate(database), MailMoveStore(database))
        }
    }

    private fun populate(database: MailDatabase): AccountId {
        val id = AccountIdFactory.create()
        val server =
            ServerSettings(
                "imap",
                "localhost",
                993,
                ConnectionSecurity.SSL_TLS_REQUIRED,
                AuthType.PLAIN,
                "fixture",
                null,
                null,
            )
        AccountSettingsStorage(database).insert(
            UtMailAccount(
                id,
                "Fixture",
                "fixture@example.invalid",
                server,
                server.copy(type = "smtp", port = 465),
                "fixture-key",
            ),
        )
        val storage = PersistentBackendStorage(database, id.toString())
        storage.createFolderUpdater().use { updater ->
            updater.createFolders(
                listOf(
                    FolderInfo("INBOX", "Inbox", FolderType.INBOX),
                    FolderInfo("Archive", "Archive", FolderType.REGULAR),
                ),
            )
        }
        val source = storage.getFolder("INBOX")
        source.setFolderExtraNumber("imapUidValidity", 1)
        storage.getFolder("Archive").setFolderExtraNumber("imapUidValidity", 2)
        database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)", source.id, "1", "Fixture".toByteArray())
        return id
    }
}
