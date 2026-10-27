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
    fun `retained binary bytes flags and completeness survive source folder removal and database reopen`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val raw = ByteArray(SOURCE_PAGE_BYTES * 2 + 17) { (it * 31).toByte() }
        lateinit var account: AccountId
        lateinit var id: String
        MailDatabase(path).use { database ->
            account = populate(database)
            val folder = PersistentBackendStorage(database, account.toString()).getFolder("INBOX")
            database.execute("UPDATE messages SET mime=?,effective_date=42 WHERE folder=? AND uid='1'", raw, folder.id)
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','X_DOWNLOADED_FULL')", folder.id)
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','SEEN')", folder.id)
            val store = MailMoveStore(database)
            val queued = store.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
            id = queued.id
            store.finish(store.claim(queued), confirmed = true)
            PersistentBackendStorage(database, account.toString()).createFolderUpdater().use {
                it.deleteFolders(listOf("INBOX"))
            }
        }
        MailDatabase(path).use { database ->
            val testSubject = MailMovePayloadStore(database)
            assertThat(testSubject.info(account, id)).isEqualTo(MailMovePayloadInfo(raw.size.toLong(), true, 42L))
            assertThat(testSubject.flags(account, id)).isEqualTo(setOf(Flag.X_DOWNLOADED_FULL, Flag.SEEN))
            var offset: Long? = 0
            val recovered = StringBuilder()
            while (offset != null) {
                val page = testSubject.page(account, id, offset)
                recovered.append(page.text)
                offset = page.nextOffset
            }
            assertThat(recovered.toString()).isEqualTo(escapedSourceBytes(raw))
            val foreign = AccountIdFactory.create()
            assertThat(testSubject.info(foreign, id)).isEqualTo(null)
            assertThat(runCatching { testSubject.page(foreign, id, 0) }.isFailure).isTrue()
            assertThat(runCatching { testSubject.page(account, id, Long.MAX_VALUE) }.isFailure).isTrue()
        }
    }

    @Test
    fun `payload release requires exact complete destination cache with original context and generation`() = fixture {
            database,
            account,
            store,
        ->
        val cache = PersistentBackendStorage(database, account.toString())
        val source = cache.getFolder("INBOX")
        val target = cache.getFolder("Archive")
        database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','X_DOWNLOADED_FULL')", source.id)
        val queued = store.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
        val complete = store.finish(store.claim(queued), confirmed = true)
        val testSubject = MailMovePayloadStore(database)
        database.execute(
            "INSERT INTO messages(folder,uid,mime,origin_binding) VALUES(?,'77',?,?)",
            target.id,
            "Wrong".toByteArray(),
            complete.binding,
        )
        assertThat(runCatching { testSubject.releaseAfterDestinationCache(complete, "77") }.isFailure).isTrue()
        database.execute(
            "UPDATE messages SET mime=?,origin_binding='foreign' WHERE folder=?",
            "Fixture".toByteArray(),
            target.id,
        )
        assertThat(runCatching { testSubject.releaseAfterDestinationCache(complete, "77") }.isFailure).isTrue()
        database.execute("UPDATE messages SET origin_binding=? WHERE folder=?", complete.binding, target.id)
        target.setFolderExtraNumber("imapUidValidity", 99)
        assertThat(runCatching { testSubject.releaseAfterDestinationCache(complete, "77") }.isFailure).isTrue()
        target.setFolderExtraNumber("imapUidValidity", complete.targetValidity)
        assertThat(runCatching { testSubject.releaseAfterDestinationCache(complete, "77") }.isFailure).isTrue()
        database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'77','X_DOWNLOADED_FULL')", target.id)
        testSubject.releaseAfterDestinationCache(complete, "77")
        testSubject.releaseAfterDestinationCache(complete, "77")
        assertThat(testSubject.info(account, complete.id)).isEqualTo(null)
        assertThat(testSubject.flags(account, complete.id)).isEqualTo(emptySet())
        assertThat(store.get(account, complete.id).state).isEqualTo(MailMoveState.COMPLETE)
    }

    @Test
    fun `failed snapshot write rolls back claim and retained data and prevents network submission`() = fixture {
            database,
            account,
            store,
        ->
        val source = PersistentBackendStorage(database, account.toString()).getFolder("INBOX")
        database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,'1','SEEN')", source.id)
        database.execute(
            "CREATE TRIGGER fail_payload BEFORE INSERT ON move_payload_flags " +
                "BEGIN SELECT RAISE(ABORT,'sensitive'); END",
        )
        val queued = store.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
        var calls = 0
        val runner = MailMoveRunner(store) { _, _, submit ->
            submit()
            calls++
        }
        assertThat(runner.run(queued, MailOperation())).isEqualTo(queued)
        assertThat(calls).isEqualTo(0)
        assertThat(MailMovePayloadStore(database).info(account, queued.id)).isEqualTo(null)
        assertThat(source.isMessagePresent("1")).isTrue()
    }

    @Test
    fun `schema fourteen backfills only provable original source generations for existing attempts`() {
        listOf(false, true).forEach { changedGeneration ->
            val path = directory.root.toPath().resolve("legacy-$changedGeneration.sqlite")
            lateinit var account: AccountId
            lateinit var id: String
            MailDatabase(path).use { database ->
                account = populate(database)
                val store = MailMoveStore(database)
                id = store.enqueue(MailMessageKey(account, "INBOX", "1"), "Archive").id
                store.claim(store.get(account, id))
                if (changedGeneration) {
                    PersistentBackendStorage(
                        database,
                        account.toString(),
                    ).getFolder("INBOX").setFolderExtraNumber("imapUidValidity", 99)
                }
                database.execute("DROP TABLE move_payload_flags")
                database.execute("DROP TABLE move_payloads")
                database.execute("PRAGMA user_version=14")
            }
            MailDatabase(path).use { database ->
                val info = MailMovePayloadStore(database).info(account, id)
                assertThat(info != null).isEqualTo(!changedGeneration)
                if (info != null) assertThat(info.complete).isEqualTo(false)
                val action = MailMoveStore(database).get(account, id)
                assertThat(action.state).isEqualTo(MailMoveState.UNKNOWN)
                assertThat(
                    runCatching {
                        MailMovePayloadStore(database).releaseAfterDestinationCache(action, "1")
                    }.isFailure,
                ).isTrue()
            }
        }
    }

    @Test
    fun `schema thirteen cache bytes survive upgrade and are not implicitly assigned a current origin`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        lateinit var account: AccountId
        MailDatabase(path).use { database ->
            account = populate(database)
            database.execute("ALTER TABLE messages DROP COLUMN origin_binding")
            database.execute("PRAGMA user_version=13")
        }
        MailDatabase(path).use { database ->
            val cache = PersistentBackendStorage(database, account.toString())
            assertThat(cache.getFolder("INBOX").messageBinding("1")).isEqualTo("")
            assertThat(
                database.query("SELECT mime FROM messages") {
                    getBlob(0).decodeToString()
                }.single(),
            ).isEqualTo("Fixture")
            assertThat(
                runCatching {
                    MailMoveStore(database).enqueue(MailMessageKey(account, "INBOX", "1"), "Archive")
                }.isFailure,
            ).isTrue()
        }
    }

    @Test
    fun `new intent after server change rejects old cached bytes despite matching mailbox generations`() = fixture {
            database,
            account,
            store,
        ->
        val settings = AccountSettingsStorage(database)
        val original = settings.all().single()
        val replacement = original.copy(incoming = original.incoming.copy(host = "other.example.invalid"))
        settings.update(replacement)
        val cache = PersistentBackendStorage(database, account.toString())
        cache.getFolder("Archive").setFolderExtraString(CACHE_BINDING_KEY, mailCacheBinding(replacement))
        val key = MailMessageKey(account, "INBOX", "1")
        assertThat(runCatching { store.enqueue(key, "Archive") }.isFailure).isTrue()
        assertThat(
            database.query("SELECT mime FROM messages") {
                getBlob(0).decodeToString()
            }.single(),
        ).isEqualTo("Fixture")
        assertThat(cache.getFolder("INBOX").messageBinding("1")).isEqualTo(mailCacheBinding(original))
    }

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
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(15L)
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
        val account = UtMailAccount(
            id,
            "Fixture",
            "fixture@example.invalid",
            server,
            server.copy(type = "smtp", port = 465),
            "fixture-key",
        )
        AccountSettingsStorage(database).insert(account)
        val binding = mailCacheBinding(account)
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
        source.setFolderExtraString(CACHE_BINDING_KEY, binding)
        storage.getFolder("Archive").setFolderExtraNumber("imapUidValidity", 2)
        storage.getFolder("Archive").setFolderExtraString(CACHE_BINDING_KEY, binding)
        database.execute(
            "INSERT INTO messages(folder,uid,mime,origin_binding) VALUES(?,?,?,?)",
            source.id,
            "1",
            "Fixture".toByteArray(),
            binding,
        )
        return id
    }
}
