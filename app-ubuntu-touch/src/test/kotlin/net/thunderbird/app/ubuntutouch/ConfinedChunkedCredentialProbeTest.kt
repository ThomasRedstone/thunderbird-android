package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedChunkedCredentialProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `probe lifecycle reopens rotates and removes all owned synthetic payloads`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val backend = FakeCredentialStore()
        MailDatabase(path).use { database ->
            val testSubject = ChunkedCredentialStore(backend, ProtectedPayloadJournal(database))
            ConfinedChunkedCredentialProbe.exercise(database, testSubject, "synthetic", "prepare")
            assertThat(backend.values.size).isEqualTo(50)
        }
        MailDatabase(path).use { database ->
            val testSubject = ChunkedCredentialStore(backend, ProtectedPayloadJournal(database))
            ConfinedChunkedCredentialProbe.exercise(database, testSubject, "synthetic", "verify-remove")
            assertThat(backend.values.isEmpty()).isTrue()
        }
    }

    @Test
    fun `fixture validation rejects unknown files and symlinked metadata before cleanup`() {
        val parent = directory.root.toPath().toRealPath()
        val path = Files.createTempDirectory(parent, "chunk-runtime-")
        val testSubject = ConfinedChunkedCredentialProbe
        assertThat(testSubject.requireFixture(parent, path)).isEqualTo(path)
        val foreign = Files.writeString(path.resolve("foreign"), "Keep")
        assertThat(runCatching { testSubject.requireFixture(parent, path) }.isFailure).isTrue()
        Files.delete(foreign)
        val target = Files.writeString(parent.resolve("outside"), "Keep")
        Files.createSymbolicLink(path.resolve("owner"), target)
        assertThat(runCatching { testSubject.requireFixture(parent, path) }.isFailure).isTrue()
        assertThat(Files.readString(target)).isEqualTo("Keep")
    }
}
