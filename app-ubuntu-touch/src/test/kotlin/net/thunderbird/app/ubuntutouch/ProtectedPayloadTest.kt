package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectedPayloadTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `manifest is small root bound and detects changed payload bytes`() {
        val value = ByteArray(39997) { (it % 251).toByte() }
        val generation = UUID.randomUUID()
        ProtectedPayloadManifest.create("fixture", generation, value).use { manifest ->
            val encoded = manifest.encode()
            assertThat(encoded.size <= PROTECTED_CHUNK_BYTES).isTrue()
            ProtectedPayloadManifest.decode("fixture", encoded).use { testSubject ->
                assertThat(testSubject.generation).isEqualTo(generation)
                assertThat(testSubject.chunks).isEqualTo(40)
                testSubject.verify(value)
                value[0] = (value[0] + 1).toByte()
                assertThat(runCatching { testSubject.verify(value) }.isFailure).isTrue()
                assertThat(runCatching { testSubject.verify(value.copyOf(8192)) }.isFailure).isTrue()
                assertThat(testSubject.toString()).isEqualTo("ProtectedPayloadManifest(redacted)")
            }
            assertThat(runCatching { ProtectedPayloadManifest.decode("other", encoded) }.isFailure).isTrue()
            assertThat(runCatching { ProtectedPayloadManifest.decode("fixture", encoded + 0) }.isFailure).isTrue()
            assertThat(
                runCatching {
                    ProtectedPayloadManifest.decode("fixture", encoded.copyOf(10))
                }.isFailure,
            ).isTrue()
        }
    }

    @Test
    fun `manifest handles empty payload and rejects oversized input and bad formats`() {
        ProtectedPayloadManifest.create("fixture", UUID.randomUUID(), byteArrayOf()).use { testSubject ->
            assertThat(testSubject.chunks).isEqualTo(0)
            testSubject.verify(byteArrayOf())
            val encoded = testSubject.encode()
            encoded[0] = 0
            assertThat(runCatching { ProtectedPayloadManifest.decode("fixture", encoded) }.isFailure).isTrue()
        }
        assertThat(
            runCatching {
                ProtectedPayloadManifest.create("fixture", UUID.randomUUID(), ByteArray(40001))
            }.isFailure,
        ).isTrue()
    }

    @Test
    fun `internal aliases cannot be supplied as roots or overlap another generation`() {
        val generation = UUID.randomUUID()
        val root = protectedManifestKey("fixture")
        assertThat(runCatching { protectedManifestKey(root) }.isFailure).isTrue()
        assertThat(protectedChunkKey("fixture", generation, 0) == root).isFalse()
        assertThat(protectedChunkKey("other", generation, 0) == protectedChunkKey("fixture", generation, 0)).isFalse()
        assertThat(
            protectedChunkKey("fixture", UUID.randomUUID(), 0) == protectedChunkKey("fixture", generation, 0),
        ).isFalse()
        assertThat(runCatching { protectedChunkKey("fixture", generation, 40) }.isFailure).isTrue()
    }

    @Test
    fun `ownership and deletion tombstone survive restart without secret data`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        lateinit var first: ProtectedPayloadGeneration
        MailDatabase(path).use { database ->
            val testSubject = ProtectedPayloadJournal(database)
            first = testSubject.begin("fixture", 39997)
            assertThat(first.chunks).isEqualTo(40)
            assertThat(testSubject.generations("other")).isEqualTo(emptyList())
            testSubject.markDeleting("fixture")
        }
        MailDatabase(path).use { database ->
            val testSubject = ProtectedPayloadJournal(database)
            assertThat(testSubject.generations("fixture")).isEqualTo(listOf(first))
            assertThat(testSubject.isDeleting("fixture")).isTrue()
            assertThat(runCatching { testSubject.begin("fixture", 8192) }.isFailure).isTrue()
            assertThat(runCatching { testSubject.finishDelete("fixture") }.isFailure).isTrue()
            assertThat(runCatching { testSubject.forget("other", first) }.isFailure).isTrue()
            testSubject.forget("fixture", first)
            testSubject.finishDelete("fixture")
            assertThat(testSubject.isDeleting("fixture")).isFalse()
            assertThat(testSubject.generations("fixture")).isEqualTo(emptyList())
            assertThat(database.query("PRAGMA table_info(protected_payload_generations)") { getText(1) })
                .isEqualTo(listOf("root", "generation", "chunks"))
        }
    }

    @Test
    fun `failed ownership insertion rolls back root and never permits invalid counts`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val testSubject = ProtectedPayloadJournal(database)
            database.execute(
                "CREATE TRIGGER fail_generation BEFORE INSERT ON protected_payload_generations " +
                    "BEGIN SELECT RAISE(ABORT,'fixture'); END",
            )
            assertThat(runCatching { testSubject.begin("fixture", 8192) }.isFailure).isTrue()
            assertThat(
                database.query("SELECT count(*) FROM protected_payload_roots") {
                    getLong(0)
                }.single(),
            ).isEqualTo(0L)
            assertThat(runCatching { testSubject.begin("fixture", 40001) }.isFailure).isTrue()
        }
    }

    @Test
    fun `schema seventeen migration preserves existing identity index`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        MailDatabase(path).use { database ->
            SignonIdentityIndex(database).put("legacy", 42)
            database.execute("DROP TABLE protected_payload_generations")
            database.execute("DROP TABLE protected_payload_roots")
            database.execute("PRAGMA user_version=17")
        }
        MailDatabase(path).use { database ->
            val testSubject = ProtectedPayloadJournal(database)
            assertThat(SignonIdentityIndex(database).find("legacy")).isEqualTo(42L)
            assertThat(testSubject.begin("fixture", 8192).chunks).isEqualTo(8)
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(19L)
        }
    }
}
