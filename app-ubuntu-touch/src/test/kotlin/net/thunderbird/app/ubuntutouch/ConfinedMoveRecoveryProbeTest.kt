package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedMoveRecoveryProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `synthetic move claim recovers retained bytes after cache removal`() {
        val root = directory.newFolder("move-runtime-host").toPath()
        ConfinedMoveRecoveryProbe.prepareFixture(root)
        ConfinedMoveRecoveryProbe.verifyFixture(root)
    }

    @Test
    fun `uncommitted fixture changes cannot replace the committed recovery snapshot`() {
        val root = directory.newFolder("move-runtime-rollback").toPath()
        val failure = runCatching {
            ConfinedMoveRecoveryProbe.prepareFixture(root) { database ->
                database.transaction {
                    database.execute("UPDATE move_payloads SET mime=?", "Uncommitted corruption".toByteArray())
                    database.execute("UPDATE mail_moves SET state='COMPLETE'")
                    error("Interrupt transaction")
                }
            }
        }
        assertThat(failure.isFailure).isTrue()
        ConfinedMoveRecoveryProbe.verifyFixture(root)
    }

    @Test
    fun `fixture reopen rejects symlink and sibling directory`() {
        val parent = directory.newFolder("probes").toPath()
        val sibling = directory.newFolder("move-runtime-outside").toPath()
        val link = parent.resolve("move-runtime-link")
        Files.createSymbolicLink(link, sibling)
        assertThat(runCatching { requireMoveFixtureDirectory(parent, link) }.isFailure).isTrue()
        assertThat(runCatching { requireMoveFixtureDirectory(parent, sibling) }.isFailure).isTrue()
    }
}
