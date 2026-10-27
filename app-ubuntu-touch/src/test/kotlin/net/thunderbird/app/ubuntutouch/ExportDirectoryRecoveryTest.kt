package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportDirectoryRecoveryTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `trusted missing exports root is recreated and stale ready metadata can be released`() {
        val exports = privateExportDirectory(directory.root.toPath().resolve("exports"))
        val journalRoot = directory.root.toPath().resolve("journal")
        val journal = ContentExportJournal(journalRoot, exports)
        val file = Files.createDirectory(exports.resolve("attachment-fixture")).resolve("message.eml")
        val reserved = journal.reserve(file, AccountIdFactory.create())
        Files.write(file, byteArrayOf(65))
        val record = journal.ready(reserved)
        Files.delete(file)
        Files.delete(file.parent)
        Files.delete(exports)
        val reopenedRoot = privateExportDirectory(exports)
        val testSubject = ContentExportJournal(journalRoot, reopenedRoot)
        testSubject.release(record)
        assertThat(testSubject.records()).isEqualTo(emptyList())
        assertThat(Files.getPosixFilePermissions(exports)).isEqualTo(PosixFilePermissions.fromString("rwx------"))
    }

    @Test
    fun `export root symlink is rejected without changing its target permissions`() {
        val target = directory.newFolder("unowned").toPath()
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-x---"))
        val root = directory.root.toPath().resolve("exports")
        Files.createSymbolicLink(root, target)
        assertThat(runCatching { privateExportDirectory(root) }.isFailure).isTrue()
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"))
    }
}
