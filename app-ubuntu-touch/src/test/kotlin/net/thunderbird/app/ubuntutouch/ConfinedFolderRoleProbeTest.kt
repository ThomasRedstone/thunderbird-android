package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedFolderRoleProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `synthetic folder roles recover and invalidate after connection changes`() {
        val root = directory.newFolder("folder-role-runtime-host").toPath()
        ConfinedFolderRoleProbe.prepareFixture(root)
        ConfinedFolderRoleProbe.verifyFixture(root)
    }

    @Test
    fun `fixture reopen rejects symlink and sibling directory`() {
        val parent = directory.newFolder("probes").toPath()
        val sibling = directory.newFolder("folder-role-runtime-outside").toPath()
        val link = parent.resolve("folder-role-runtime-link")
        Files.createSymbolicLink(link, sibling)
        assertThat(runCatching { requireFolderRoleFixtureDirectory(parent, link) }.isFailure).isTrue()
        assertThat(runCatching { requireFolderRoleFixtureDirectory(parent, sibling) }.isFailure).isTrue()
    }
}
