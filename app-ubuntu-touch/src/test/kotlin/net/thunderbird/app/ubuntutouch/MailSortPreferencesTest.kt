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
    fun `scoped preferences migrate legacy defaults and isolate accounts folders and search scopes`() {
        val path = directory.root.toPath().resolve("mail-sort")
        val first = AccountIdFactory.create()
        val second = AccountIdFactory.create()
        val contexts = listOf(
            MailSortContext(CachedSearchScope.FOLDER, first, "INBOX"),
            MailSortContext(CachedSearchScope.FOLDER, first, "../Archive/\u0000" + "x".repeat(1000)),
            MailSortContext(CachedSearchScope.FOLDER, second, "INBOX"),
            MailSortContext(CachedSearchScope.ACCOUNT, first),
            MailSortContext(CachedSearchScope.ACCOUNT, second),
            MailSortContext(CachedSearchScope.ALL_ACCOUNTS),
        )
        val testSubject = MailSortPreferences(path)
        testSubject.save(MailSort.SENDER)
        contexts.forEach { assertThat(testSubject.read(it)).isEqualTo(MailSort.SENDER) }
        contexts.zip(MailSort.entries).forEach { (context, sort) -> testSubject.save(sort, context) }
        contexts.zip(MailSort.entries).forEach { (context, sort) ->
            assertThat(MailSortPreferences(path).read(context)).isEqualTo(sort)
        }
        assertThat(testSubject.read()).isEqualTo(MailSort.SENDER)
        val files = directory.root.listFiles()!!.toList()
        assertThat(files.size).isEqualTo(7)
        assertThat(files.all { it.isFile && it.name.length <= 74 }).isTrue()
        assertThat(
            files.all {
                Files.getPosixFilePermissions(it.toPath()) == PosixFilePermissions.fromString("rw-------")
            },
        ).isTrue()
        assertThat(contexts.all { it.toString() == "MailSortContext(redacted)" }).isTrue()
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
