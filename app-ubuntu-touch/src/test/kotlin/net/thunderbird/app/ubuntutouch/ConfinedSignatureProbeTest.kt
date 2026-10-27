package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedSignatureProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `synthetic native fixture migrates composes and reopens without credentials or network`() {
        val root = directory.newFolder("signature-runtime-host").toPath()
        ConfinedSignatureProbe.prepareFixture(root)
        ConfinedSignatureProbe.verifyFixture(root, create = true)
        ConfinedSignatureProbe.verifyFixture(root, create = false)
    }

    @Test
    fun `fixture reopen rejects symlink and sibling directory`() {
        val parent = directory.newFolder("probes").toPath()
        val sibling = directory.newFolder("signature-runtime-outside").toPath()
        val link = parent.resolve("signature-runtime-link")
        Files.createSymbolicLink(link, sibling)
        assertThat(runCatching { requireSignatureFixtureDirectory(parent, link) }.isFailure).isTrue()
        assertThat(runCatching { requireSignatureFixtureDirectory(parent, sibling) }.isFailure).isTrue()
    }
}
