package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedAttachmentProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `synthetic attachment bytes and uncertain journal recover without replay`() {
        val root = directory.newFolder("attachment-runtime-host").toPath()
        val previous = com.fsck.k9.mail.internet.BinaryTempFileBody.getTempDirectory()
        try {
            ConfinedAttachmentProbe.prepareFixture(root)
            ConfinedAttachmentProbe.verifyFixture(root)
        } finally {
            com.fsck.k9.mail.internet.BinaryTempFileBody.setTempDirectory(previous)
        }
    }

    @Test
    fun `fixture reopen rejects symlink and sibling directory`() {
        val parent = directory.newFolder("probes").toPath()
        val sibling = directory.newFolder("attachment-runtime-outside").toPath()
        val link = parent.resolve("attachment-runtime-link")
        Files.createSymbolicLink(link, sibling)
        assertThat(runCatching { requireAttachmentFixtureDirectory(parent, link) }.isFailure).isTrue()
        assertThat(runCatching { requireAttachmentFixtureDirectory(parent, sibling) }.isFailure).isTrue()
    }
}
