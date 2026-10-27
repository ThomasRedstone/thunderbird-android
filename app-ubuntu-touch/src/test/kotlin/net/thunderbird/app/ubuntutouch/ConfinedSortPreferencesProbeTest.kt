package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedSortPreferencesProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `sort probe refuses unconfined execution before accessing fixture storage`() {
        val testSubject = runCatching {
            ConfinedSortPreferencesProbe.main(arrayOf("mail-demo.thomasredstone_mail-demo_0.1.26", "prepare"))
        }
        assertThat(testSubject.exceptionOrNull()?.message).isEqualTo("Required application confinement unavailable")
    }

    @Test
    fun `sort fixture recovery rejects outside directories and symlink aliases`() {
        val parent = Files.createDirectory(directory.root.toPath().resolve("probes"))
        val valid = Files.createDirectory(parent.resolve("sort-runtime-owned"))
        val outside = Files.createDirectory(directory.root.toPath().resolve("sort-runtime-outside"))
        val link = Files.createSymbolicLink(parent.resolve("sort-runtime-link"), outside)
        Files.writeString(outside.resolve("keep"), "retained")
        assertThat(requireSortFixtureDirectory(parent, valid)).isEqualTo(valid)
        listOf(outside, link, parent).forEach {
            assertThat(runCatching { requireSortFixtureDirectory(parent, it) }.isFailure).isTrue()
        }
        assertThat(Files.readString(outside.resolve("keep"))).isEqualTo("retained")
    }
}
