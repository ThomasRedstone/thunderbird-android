package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import java.nio.charset.StandardCharsets
import java.util.UUID
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UtAccountManagerTest {
    @get:Rule val directory = TemporaryFolder()
    private val passwords = AccountPasswords("incoming-fixture-password", "outgoing-fixture-password")

    @Test
    fun `account settings and separate protected credentials survive reopening without plaintext database passwords`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val secrets = FakeCredentialStore()
        val account = account()
        MailDatabase(path).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            testSubject.createWithPasswords(account, passwords)
        }
        MailDatabase(path).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            assertThat(testSubject.findAll()).isEqualTo(listOf(account))
            assertThat(testSubject.passwords(account.id).incoming).isEqualTo(passwords.incoming)
        }
        val databaseText = String(path.toFile().readBytes(), StandardCharsets.ISO_8859_1)
        assertThat(passwords.incoming in databaseText || passwords.outgoing in databaseText).isFalse()
        assertThat(passwords.incoming in account.toString()).isFalse()
    }

    @Test
    fun `locked credential storage never creates an account or plaintext fallback`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val secrets = FakeCredentialStore().apply { locked = true }
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            val result = runCatching { testSubject.createWithPasswords(account(), passwords) }
            assertThat((result.exceptionOrNull() as SecretStoreException).reason).isEqualTo(SecretFailure.LOCKED)
            assertThat(testSubject.findAll()).isEmpty()
            assertThat(secrets.values).isEmpty()
        }
    }

    @Test
    fun `failed database commit leaves journaled credential that can be cleaned after reopen`() {
        val secrets = FakeCredentialStore()
        val path = directory.root.toPath().resolve("mail.sqlite")
        MailDatabase(path).use { database ->
            database.execute(
                "CREATE TRIGGER fail_account BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'fixture'); END",
            )
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            assertThat(runCatching { testSubject.createWithPasswords(account(), passwords) }.isFailure).isTrue()
            assertThat(secrets.values.size).isEqualTo(1)
        }
        MailDatabase(path).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            testSubject.cleanupUnusedCredentials()
            assertThat(secrets.values).isEmpty()
            assertThat(testSubject.findAll()).isEmpty()
        }
    }

    @Test
    fun `removal refuses locked storage then removes only selected account and its mail`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val secrets = FakeCredentialStore()
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            val first = account()
            val other = account()
            listOf(first, other).forEach { account ->
                testSubject.createWithPasswords(account, passwords)
                PersistentBackendStorage(database, account.id.toString()).createFolderUpdater().use {
                    it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
                }
            }
            secrets.locked = true
            assertThat(runCatching { testSubject.delete(first.id) }.isFailure).isTrue()
            assertThat(testSubject.findAll().size).isEqualTo(2)
            secrets.locked = false
            testSubject.delete(first.id)
            assertThat(testSubject.findAll()).isEqualTo(listOf(other))
            assertThat(PersistentBackendStorage(database, first.id.toString()).getFolderServerIds()).isEmpty()
            assertThat(
                PersistentBackendStorage(database, other.id.toString()).getFolderServerIds(),
            ).isEqualTo(listOf("INBOX"))
            assertThat(secrets.values.size).isEqualTo(1)
        }
    }

    @Test
    fun `password replacement switches reference atomically and removes obsolete credential`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val secrets = FakeCredentialStore()
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            val account = account()
            testSubject.createWithPasswords(account, passwords)
            testSubject.replacePasswords(account.id, AccountPasswords("replacement-in", "replacement-out"))
            assertThat(testSubject.findById(account.id)?.credentialKey).isNotEqualTo(account.credentialKey)
            assertThat(testSubject.passwords(account.id).incoming).isEqualTo("replacement-in")
            assertThat(secrets.values.size).isEqualTo(1)
        }
    }

    @Test
    fun `failed replacement preserves the original credential reference and password`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val secrets = FakeCredentialStore()
            val testSubject = UtAccountManager(AccountSettingsStorage(database), secrets)
            val account = account()
            testSubject.createWithPasswords(account, passwords)
            secrets.locked = true
            assertThat(
                runCatching {
                    testSubject.replacePasswords(account.id, AccountPasswords("new-in", "new-out"))
                }.isFailure,
            ).isTrue()
            secrets.locked = false
            assertThat(testSubject.findById(account.id)?.credentialKey).isEqualTo(account.credentialKey)
            assertThat(testSubject.passwords(account.id).incoming).isEqualTo(passwords.incoming)
            assertThat(testSubject.cleanupUnusedCredentials()).isTrue()
            assertThat(secrets.values.size).isEqualTo(1)
        }
    }

    @Test
    fun `schema one upgrades without losing cached folders or sync checkpoints`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        MailDatabase(path).use { database ->
            val backend = PersistentBackendStorage(database, "existing-account")
            backend.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            backend.setExtraNumber("checkpoint", 42)
            // Reconstruct the previously committed schema boundary, retaining the real mail tables.
            database.execute("DROP TABLE sent_copies")
            database.execute("DROP TABLE sent_settings")
            database.execute("DROP TABLE signon_identity_index")
            database.execute("DROP TABLE servers")
            database.execute("DROP TABLE outgoing")
            database.execute("DROP TABLE accounts")
            database.execute("DROP TABLE credential_cleanup")
            if ("in_reply_to" in database.query("PRAGMA table_info(outgoing)") { getText(1) }) {
                database.execute("ALTER TABLE outgoing DROP COLUMN in_reply_to")
                database.execute("ALTER TABLE outgoing DROP COLUMN thread_references")
            }
            database.execute("DROP TABLE pending_flags")
            database.execute("PRAGMA user_version=1")
        }
        MailDatabase(path).use { database ->
            val testSubject = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
            assertThat(testSubject.findAll()).isEmpty()
            val backend = PersistentBackendStorage(database, "existing-account")
            assertThat(backend.getFolderServerIds()).isEqualTo(listOf("INBOX"))
            assertThat(backend.getExtraNumber("checkpoint")).isEqualTo(42L)
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(14L)
        }
    }

    @Test
    fun `TLS disabled or plaintext embedded in server settings is rejected`() {
        val account = account()
        assertThat(
            runCatching {
                account.copy(incoming = account.incoming.copy(connectionSecurity = ConnectionSecurity.NONE))
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                account.copy(outgoing = account.outgoing.copy(password = "not-allowed"))
            }.isFailure,
        ).isTrue()
    }

    private fun account(): UtMailAccount = UtMailAccount(
        AccountIdFactory.create(),
        "Fixture",
        "fixture@example.invalid",
        server("imap", 993),
        server("smtp", 465),
        UUID.randomUUID().toString(),
    )

    private fun server(kind: String, port: Int) = ServerSettings(
        kind,
        "localhost",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture",
        null,
        null,
    )
}

internal class FakeCredentialStore : CredentialStore {
    val values = mutableMapOf<String, ByteArray>()
    var locked = false
    override fun read(key: String): ByteArray? {
        checkAvailable()
        return values[key]?.copyOf()
    }
    override fun write(key: String, value: ByteArray) {
        checkAvailable()
        values[key] = value.copyOf()
    }
    override fun delete(key: String) {
        checkAvailable()
        values.remove(key)?.fill(0)
    }
    private fun checkAvailable() {
        if (locked) throw SecretStoreException(SecretFailure.LOCKED)
    }
}
