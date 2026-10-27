package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailSortPreferencesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `missing and unknown bounded values default and all modes survive reopening`() {
        val path = directory.root.toPath().resolve("mail-sort")
        val testSubject = MailSortPreferences(path)
        assertThat(testSubject.read()).isEqualTo(MailSort.NEWEST)
        listOf("future-mode", "SUBJECT" + "x".repeat(1000)).forEach {
            Files.writeString(path, it)
            assertThat(testSubject.read()).isEqualTo(MailSort.NEWEST)
        }
        MailSort.entries.forEach {
            testSubject.save(it)
            assertThat(MailSortPreferences(path).read()).isEqualTo(it)
            assertThat(Files.getPosixFilePermissions(path))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"))
        }
        assertThat(directory.root.list()!!.toList()).isEqualTo(listOf("mail-sort"))
    }

    @Test
    fun `failed replacement cleans provisional file and preserves destination`() {
        val path = directory.root.toPath().resolve("mail-sort")
        Files.createDirectory(path)
        val sentinel = Files.writeString(path.resolve("keep"), "retained")
        val testSubject = MailSortPreferences(path)
        assertThat(runCatching { testSubject.save(MailSort.SUBJECT) }.isFailure).isTrue()
        assertThat(Files.readString(sentinel)).isEqualTo("retained")
        assertThat(directory.root.list()!!.toList()).isEqualTo(listOf("mail-sort"))
    }
}
