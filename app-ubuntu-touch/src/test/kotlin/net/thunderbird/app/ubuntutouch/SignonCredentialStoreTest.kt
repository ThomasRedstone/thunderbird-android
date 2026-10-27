package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignonCredentialStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `index survives reopen and is committed before the secret is written`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val client = FakeSignonClient()
        MailDatabase(path).use { database ->
            val index = SignonIdentityIndex(database)
            client.beforeWrite = { id, key -> assertThat(index.find(key)).isEqualTo(id) }
            val testSubject = SignonCredentialStore(index) { client }
            testSubject.write("fixture", byteArrayOf(1, 2, 3))
        }
        MailDatabase(path).use { database ->
            val testSubject = SignonCredentialStore(SignonIdentityIndex(database)) { client }
            assertThat(testSubject.read("fixture")!!.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
            testSubject.delete("fixture")
            assertThat(testSubject.read("fixture")).isNull()
            assertThat(client.records).isEmpty()
        }
    }

    @Test
    fun `index commit failure never sends a password and removes empty reservation`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            database.execute(
                "CREATE TRIGGER fail_index BEFORE INSERT ON signon_identity_index " +
                    "BEGIN SELECT RAISE(ABORT,'fixture'); END",
            )
            val client = FakeSignonClient()
            val testSubject = SignonCredentialStore(SignonIdentityIndex(database)) { client }
            assertThat(runCatching { testSubject.write("fixture", byteArrayOf(1)) }.isFailure).isTrue()
            assertThat(client.writes).isEqualTo(0)
            assertThat(client.records).isEmpty()
        }
    }

    @Test
    fun `failed secret store preserves an indexed reservation for cleanup after restart`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val client = FakeSignonClient().apply { unavailable = true }
        MailDatabase(path).use { database ->
            val index = SignonIdentityIndex(database)
            val testSubject = SignonCredentialStore(index) { client }
            assertThat(runCatching { testSubject.write("fixture", byteArrayOf(1)) }.isFailure).isTrue()
            assertThat(index.find("fixture")).isNotNull()
            assertThat(client.records.values.single().value).isNull()
        }
        client.unavailable = false
        MailDatabase(path).use { database ->
            val testSubject = SignonCredentialStore(SignonIdentityIndex(database)) { client }
            testSubject.delete("fixture")
            assertThat(client.records).isEmpty()
        }
    }

    @Test
    fun `failed deletion retains index and can be retried without deleting another identity`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val index = SignonIdentityIndex(database)
            val client = FakeSignonClient()
            val testSubject = SignonCredentialStore(index) { client }
            testSubject.write("first", byteArrayOf(1))
            testSubject.write("second", byteArrayOf(2))
            client.unavailable = true
            assertThat(runCatching { testSubject.delete("first") }.isFailure).isTrue()
            assertThat(index.find("first")).isNotNull()
            client.unavailable = false
            testSubject.delete("first")
            assertThat(testSubject.read("second")!!.single()).isEqualTo(2.toByte())
            assertThat(client.records.size).isEqualTo(1)
        }
    }

    @Test
    fun `metadata ownership mismatch is not treated as missing identity`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val index = SignonIdentityIndex(database)
            val client = FakeSignonClient()
            val testSubject = SignonCredentialStore(index) { client }
            testSubject.write("first", byteArrayOf(1))
            index.put("other", index.find("first")!!)
            assertThat(runCatching { testSubject.delete("other") }.isFailure).isTrue()
            assertThat(client.records.size).isEqualTo(1)
            assertThat(client.writes).isEqualTo(1)
        }
    }

    @Test
    fun `schema two upgrades without replacing account tables`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        MailDatabase(path).use { database ->
            database.execute("DROP TABLE outgoing")
            database.execute("DROP TABLE signon_identity_index")
            database.execute("PRAGMA user_version=2")
            database.execute("INSERT INTO credential_cleanup(key) VALUES(?)", "pending")
        }
        MailDatabase(path).use { database ->
            val testSubject = SignonIdentityIndex(database)
            testSubject.put("fixture", 42)
            assertThat(testSubject.find("fixture")).isEqualTo(42L)
            assertThat(database.query("SELECT key FROM credential_cleanup") { getText(0) }).isEqualTo(listOf("pending"))
        }
    }
}

private class FakeSignonClient : SignonClient {
    class Record(val key: String, var value: ByteArray? = null)
    val records = mutableMapOf<Long, Record>()
    var unavailable = false
    var writes = 0
    var beforeWrite: (Long, String) -> Unit = { _, _ -> }
    private var nextId = 1L
    override fun exists(id: Long, key: String): Boolean {
        val record = records[id] ?: return false
        if (record.key != key) throw SecretStoreException(SecretFailure.INVALID_REPLY)
        return true
    }
    override fun reserve(key: String): Long = nextId++.also { records[it] = Record(key) }
    override fun write(id: Long, key: String, value: ByteArray) {
        if (unavailable) throw SecretStoreException(SecretFailure.UNAVAILABLE)
        check(exists(id, key))
        beforeWrite(id, key)
        writes++
        records.getValue(id).value = value.copyOf()
    }
    override fun read(id: Long, key: String): ByteArray? {
        if (unavailable) throw SecretStoreException(SecretFailure.UNAVAILABLE)
        return if (exists(id, key)) records.getValue(id).value?.copyOf() else null
    }
    override fun delete(id: Long, key: String) {
        if (unavailable) throw SecretStoreException(SecretFailure.UNAVAILABLE)
        if (exists(id, key)) records.remove(id)
    }
    override fun close() = Unit
}
