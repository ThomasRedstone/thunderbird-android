package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedMessageSourceProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `source probe refuses unconfined execution before accessing fixture storage`() {
        val testSubject = runCatching {
            ConfinedMessageSourceProbe.main(arrayOf("mail-demo.thomasredstone_mail-demo_0.1.27", "prepare"))
        }
        assertThat(testSubject.exceptionOrNull()?.message).isEqualTo("Required application confinement unavailable")
    }

    @Test
    fun `source fixture recovery rejects outside directories and symlink aliases`() {
        val parent = Files.createDirectory(directory.root.toPath().resolve("probes"))
        val valid = Files.createDirectory(parent.resolve("source-runtime-owned"))
        val outside = Files.createDirectory(directory.root.toPath().resolve("source-runtime-outside"))
        val link = Files.createSymbolicLink(parent.resolve("source-runtime-link"), outside)
        Files.writeString(outside.resolve("keep"), "retained")
        assertThat(requireSourceFixtureDirectory(parent, valid)).isEqualTo(valid)
        listOf(outside, link, parent).forEach {
            assertThat(runCatching { requireSourceFixtureDirectory(parent, it) }.isFailure).isTrue()
        }
        assertThat(Files.readString(outside.resolve("keep"))).isEqualTo("retained")
    }
}
