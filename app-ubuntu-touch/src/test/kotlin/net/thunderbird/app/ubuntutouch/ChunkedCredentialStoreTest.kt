package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.nio.file.Files
import java.security.MessageDigest
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkedCredentialStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `large payloads round trip through capped backend across database reopen and delete`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val backend = CappedProtectedStore()
        val value = ByteArray(39997) { (it % 251).toByte() }
        MailDatabase(path).use { database ->
            val testSubject = store(database, backend)
            testSubject.write("fixture", ByteArray(8192) { 17 })
            testSubject.write("fixture", value)
            assertThat(testSubject.hasPendingCleanup()).isFalse()
            assertThat(backend.values.size).isEqualTo(41)
            assertThat(backend.submitted.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
        }
        MailDatabase(path).use { database ->
            val testSubject = store(database, backend)
            assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(value.toList())
            assertThat(backend.returned.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
            testSubject.delete("fixture")
            assertThat(testSubject.read("fixture")).isNull()
            assertThat(backend.values.isEmpty()).isTrue()
            assertThat(testSubject.hasPendingCleanup()).isFalse()
        }
        val databaseBytes = Files.readAllBytes(path)
        assertThat(databaseBytes.containsSequence(value)).isFalse()
        assertThat(databaseBytes.containsSequence(MessageDigest.getInstance("SHA-256").digest(value))).isFalse()
    }

    @Test
    fun `partial chunks retain ownership and cleanup restores unchanged legacy bytes`() = fixture { database, backend ->
        backend.values["fixture"] = byteArrayOf(7)
        val journal = ProtectedPayloadJournal(database)
        backend.beforeWrite = { key ->
            assertThat(journal.generations("fixture").size).isEqualTo(1)
            if (key.endsWith(":1")) unavailable()
        }
        val testSubject = store(database, backend)
        assertThat(runCatching { testSubject.write("fixture", ByteArray(8192)) }.isFailure).isTrue()
        assertThat(testSubject.hasPendingCleanup()).isTrue()
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
        backend.beforeWrite = {}
        assertThat(testSubject.cleanupPending()).isTrue()
        assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(listOf<Byte>(7))
        assertThat(journal.generations("fixture")).isEqualTo(emptyList())
        assertThat(backend.values.keys).isEqualTo(setOf("fixture"))
    }

    @Test
    fun `lost pointer acknowledgement after commit retains new payload without replay`() = fixture {
            database,
            backend,
        ->
        val testSubject = store(database, backend)
        testSubject.write("fixture", byteArrayOf(1))
        backend.afterWrite = { if (it == protectedManifestKey("fixture")) unavailable() }
        assertThat(
            runCatching {
                testSubject.write("fixture", ByteArray(8192) { 2 })
            }.isFailure,
        ).isTrue()
        val writes = backend.writes
        assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(ByteArray(8192) { 2 }.toList())
        assertThat(backend.writes).isEqualTo(writes)
        backend.afterWrite = {}
        assertThat(testSubject.cleanupPending()).isTrue()
        assertThat(backend.values.size).isEqualTo(9)
    }

    @Test
    fun `failed pointer write keeps previous generation and retires only staged chunks`() = fixture {
            database,
            backend,
        ->
        val testSubject = store(database, backend)
        testSubject.write("fixture", byteArrayOf(1))
        backend.beforeWrite = { if (it == protectedManifestKey("fixture")) unavailable() }
        assertThat(runCatching { testSubject.write("fixture", ByteArray(8192)) }.isFailure).isTrue()
        assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(listOf<Byte>(1))
        backend.beforeWrite = {}
        assertThat(testSubject.cleanupPending()).isTrue()
        assertThat(backend.values.size).isEqualTo(2)
    }

    @Test
    fun `missing committed pointer never falls back or discards its generation`() = fixture { database, backend ->
        val testSubject = store(database, backend)
        testSubject.write("fixture", ByteArray(8192))
        backend.values["fixture"] = byteArrayOf(9)
        backend.values.remove(protectedManifestKey("fixture"))
        // Simulate an interrupted retirement so retry attempts classification.
        ProtectedPayloadJournal(database).begin("fixture", 0)
        val chunks = backend.values.size
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
        assertThat(testSubject.cleanupPending()).isFalse()
        assertThat(backend.values.size).isEqualTo(chunks)
        assertThat(ProtectedPayloadJournal(database).generations("fixture").size).isEqualTo(2)
    }

    @Test
    fun `corrupt missing or mixed chunks and foreign manifests fail closed`() = fixture { database, backend ->
        val testSubject = store(database, backend)
        testSubject.write("fixture", ByteArray(8192) { 1 })
        testSubject.write("other", ByteArray(8192) { 2 })
        val generation = ProtectedPayloadJournal(database).generations("fixture").single()
        val chunkKey = protectedChunkKey("fixture", generation.id, 0)
        val original = backend.values.remove(chunkKey)!!
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
        backend.values[chunkKey] = ByteArray(1024) { 2 }
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
        backend.values[chunkKey] = original.copyOf(1)
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
        backend.values[chunkKey] = original
        backend.values[protectedManifestKey("fixture")] =
            backend.values.getValue(protectedManifestKey("other")).copyOf()
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
    }

    @Test
    fun `deletion tombstone survives interruption and prevents legacy resurrection`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val backend = CappedProtectedStore()
        MailDatabase(path).use { database ->
            val testSubject = store(database, backend)
            testSubject.write("fixture", ByteArray(8192))
            backend.values["fixture"] = byteArrayOf(9)
            backend.beforeDelete = { if (it == "fixture") unavailable() }
            assertThat(runCatching { testSubject.delete("fixture") }.isFailure).isTrue()
        }
        MailDatabase(path).use { database ->
            val testSubject = store(database, backend)
            assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
            assertThat(runCatching { testSubject.write("fixture", byteArrayOf(1)) }.isFailure).isTrue()
            backend.beforeDelete = {}
            assertThat(testSubject.cleanupPending()).isTrue()
            assertThat(backend.values.isEmpty()).isTrue()
            assertThat(testSubject.read("fixture")).isNull()
        }
    }

    @Test
    fun `retirement failure is pending while confirmed replacement stays readable`() = fixture { database, backend ->
        val testSubject = store(database, backend)
        testSubject.write("fixture", byteArrayOf(1))
        testSubject.write("other", byteArrayOf(3))
        val previous = ProtectedPayloadJournal(database).generations("fixture").single()
        val oldKey = protectedChunkKey("fixture", previous.id, 0)
        backend.beforeDelete = { if (it == oldKey) unavailable() }
        testSubject.write("fixture", byteArrayOf(2))
        assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(listOf<Byte>(2))
        assertThat(testSubject.hasPendingCleanup()).isTrue()
        backend.beforeDelete = {}
        assertThat(testSubject.cleanupPending()).isTrue()
        assertThat(testSubject.read("other")!!.toList()).isEqualTo(listOf<Byte>(3))
        assertThat(ProtectedPayloadJournal(database).generations("fixture").size).isEqualTo(1)
    }

    @Test
    fun `legacy arbitrary bytes are never decoded as manifests and locked pointer cannot be overwritten`() = fixture {
            database,
            backend,
        ->
        val testSubject = store(database, backend)
        val legacy = "UTCH manifest-like arbitrary bytes".toByteArray()
        backend.values["fixture"] = legacy
        assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(legacy.toList())
        backend.locked = true
        val writes = backend.writes
        assertThat(runCatching { testSubject.write("fixture", byteArrayOf(1)) }.isFailure).isTrue()
        assertThat(backend.writes).isEqualTo(writes)
        assertThat(ProtectedPayloadJournal(database).generations("fixture")).isEqualTo(emptyList())
    }

    @Test
    fun `account cleanup owner discovers and retries retired generations`() = fixture { database, backend ->
        val testSubject = store(database, backend)
        testSubject.write("fixture", byteArrayOf(1))
        val previous = ProtectedPayloadJournal(database).generations("fixture").single()
        val oldKey = protectedChunkKey("fixture", previous.id, 0)
        backend.beforeDelete = { if (it == oldKey) unavailable() }
        testSubject.write("fixture", byteArrayOf(2))
        val manager = UtAccountManager(AccountSettingsStorage(database), testSubject)
        assertThat(manager.hasPendingCredentialCleanup()).isTrue()
        assertThat(manager.cleanupUnusedCredentials()).isFalse()
        backend.beforeDelete = {}
        assertThat(manager.cleanupUnusedCredentials()).isTrue()
        assertThat(manager.hasPendingCredentialCleanup()).isFalse()
        assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(listOf<Byte>(2))
    }

    @Test
    fun `first pointer write failure retains attempted generation until explicit deletion`() = fixture {
            database,
            backend,
        ->
        val testSubject = store(database, backend)
        backend.beforeWrite = { if (it == protectedManifestKey("fixture")) unavailable() }
        assertThat(runCatching { testSubject.write("fixture", ByteArray(8192)) }.isFailure).isTrue()
        val owned = ProtectedPayloadJournal(database).generations("fixture")
        assertThat(testSubject.cleanupPending()).isFalse()
        assertThat(ProtectedPayloadJournal(database).generations("fixture")).isEqualTo(owned)
        assertThat(runCatching { testSubject.read("fixture") }.isFailure).isTrue()
        backend.beforeWrite = {}
        testSubject.delete("fixture")
        assertThat(backend.values.isEmpty()).isTrue()
    }

    @Test
    fun `account password replacement and cleanup use the capped protected backend`() = fixture { database, backend ->
        val testSubject = store(database, backend)
        val manager = UtAccountManager(AccountSettingsStorage(database), testSubject)
        val incoming = ServerSettings(
            "imap",
            "fixture.example.invalid",
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
            incoming,
            incoming.copy(type = "smtp", port = 465),
            "fixture",
        )
        manager.createWithPasswords(account, AccountPasswords("a".repeat(3000), "b".repeat(3000)))
        assertThat(manager.passwords(account.id).incoming).isEqualTo("a".repeat(3000))
        backend.beforeDelete = { if (it == protectedManifestKey("fixture")) unavailable() }
        assertThat(manager.replacePasswords(account.id, AccountPasswords("c".repeat(3000), "d".repeat(3000)))).isFalse()
        assertThat(manager.passwords(account.id).incoming).isEqualTo("c".repeat(3000))
        assertThat(manager.hasPendingCredentialCleanup()).isTrue()
        backend.beforeDelete = {}
        assertThat(manager.cleanupUnusedCredentials()).isTrue()
        assertThat(manager.hasPendingCredentialCleanup()).isFalse()
        manager.delete(account.id)
        assertThat(backend.values.isEmpty()).isTrue()
    }

    @Test
    fun `schema eighteen gains pointer safety without losing pending ownership`() {
        val path = directory.root.toPath().resolve("schema18.sqlite")
        MailDatabase(path).use { database ->
            ProtectedPayloadJournal(database).begin("fixture", 8192)
            database.execute("ALTER TABLE protected_payload_roots DROP COLUMN cleanup_pending")
            database.execute("ALTER TABLE protected_payload_roots DROP COLUMN pointer_attempted")
            database.execute("PRAGMA user_version=18")
        }
        MailDatabase(path).use { database ->
            val testSubject = ProtectedPayloadJournal(database)
            assertThat(testSubject.generations("fixture").single().chunks).isEqualTo(8)
            assertThat(testSubject.pendingRoots()).isEqualTo(listOf("fixture"))
            assertThat(testSubject.pointerAttempted("fixture")).isFalse()
            testSubject.markPointerAttempt("fixture")
            assertThat(testSubject.pointerAttempted("fixture")).isTrue()
        }
    }

    private fun fixture(block: (MailDatabase, CappedProtectedStore) -> Unit) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { block(it, CappedProtectedStore()) }
    }

    private fun store(database: MailDatabase, backend: CappedProtectedStore) =
        ChunkedCredentialStore(backend, ProtectedPayloadJournal(database))
}

private class CappedProtectedStore : CredentialStore {
    val values = mutableMapOf<String, ByteArray>()
    val submitted = mutableListOf<ByteArray>()
    val returned = mutableListOf<ByteArray>()
    var beforeWrite: (String) -> Unit = {}
    var afterWrite: (String) -> Unit = {}
    var beforeDelete: (String) -> Unit = {}
    var writes = 0
    var locked = false

    override fun read(key: String): ByteArray? {
        if (locked) unavailable()
        val value = values[key] ?: return null
        check(value.size <= 1024) { "Native fixture read limit" }
        return value.copyOf().also { returned += it }
    }

    override fun write(key: String, value: ByteArray) {
        if (locked) unavailable()
        beforeWrite(key)
        check(value.size <= 1024)
        submitted += value
        values[key] = value.copyOf()
        writes++
        afterWrite(key)
    }

    override fun delete(key: String) {
        if (locked) unavailable()
        beforeDelete(key)
        values.remove(key)
    }
}

private fun unavailable(): Nothing = throw SecretStoreException(SecretFailure.UNAVAILABLE)

private fun ByteArray.containsSequence(other: ByteArray): Boolean =
    other.size <= size && (0..size - other.size).any { start -> other.indices.all { this[start + it] == other[it] } }
