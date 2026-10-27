package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedMessageSourceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `all binary cached bytes are reachable in bounded pages after database reopen`() {
        val path = directory.root.toPath().resolve("source.sqlite")
        val account = AccountIdFactory.create()
        val raw = ByteArray(SOURCE_PAGE_BYTES * 2 + 17) { ((it * 31) xor (it ushr 8) xor (it ushr 16)).toByte() }
        MailDatabase(path).use { database ->
            val storage = PersistentBackendStorage(database, account.toString())
            storage.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            val folder = storage.getFolder("INBOX").id
            database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)", folder, "1", raw)
            database.execute("INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')", folder, "1")
        }
        MailDatabase(path).use { database ->
            var offset: Long? = 0
            val source = StringBuilder()
            var pages = 0
            while (offset != null) {
                val testSubject = cachedMessageSource(database, account, "INBOX", "1", offset)
                assertThat(testSubject.sizeBytes <= SOURCE_PAGE_BYTES).isTrue()
                assertThat(testSubject.totalBytes).isEqualTo(raw.size.toLong())
                assertThat(testSubject.complete).isTrue()
                assertThat(testSubject.previousOffset).isEqualTo(if (offset == 0L) null else offset - SOURCE_PAGE_BYTES)
                source.append(testSubject.text)
                offset = testSubject.nextOffset
                pages++
            }
            assertThat(pages).isEqualTo(3)
            assertThat(source.toString()).isEqualTo(escapedSourceBytes(raw))
            listOf(-1L, 1L, Long.MAX_VALUE, (SOURCE_PAGE_BYTES * 3).toLong()).forEach {
                assertThat(runCatching { cachedMessageSource(database, account, "INBOX", "1", it) }.isFailure).isTrue()
            }
            assertThat(
                runCatching {
                    cachedMessageSource(database, AccountIdFactory.create(), "INBOX", "1", 0)
                }.isFailure,
            ).isTrue()
            assertThat(runCatching { cachedMessageSource(database, account, "Archive", "1", 0) }.isFailure).isTrue()
            assertThat(runCatching { cachedMessageSource(database, account, "INBOX", "missing", 0) }.isFailure).isTrue()
        }
    }

    @Test
    fun `empty incomplete cache is identified without pretending a full message was downloaded`() {
        MailDatabase(directory.root.toPath().resolve("empty.sqlite")).use { database ->
            val account = AccountIdFactory.create()
            val storage = PersistentBackendStorage(database, account.toString())
            storage.createFolderUpdater().use {
                it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX)))
            }
            database.execute(
                "INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)",
                storage.getFolder("INBOX").id,
                "1",
                byteArrayOf(),
            )
            val testSubject = cachedMessageSource(database, account, "INBOX", "1", 0)
            assertThat(testSubject.complete).isEqualTo(false)
            assertThat(testSubject.text).isEqualTo("")
            assertThat(testSubject.nextOffset).isEqualTo(null)
            assertThat(testSubject.previousOffset).isEqualTo(null)
            assertThat(testSubject.toString()).isEqualTo("MailSourcePage(redacted)")
        }
    }

    @Test
    fun `source escaping preserves printable ASCII and newlines while making control and bidi bytes explicit`() {
        val testSubject = escapedSourceBytes(byteArrayOf(0, 9, 10, 13, 27, 65, 92, 127, -1))
        assertThat(testSubject).isEqualTo("\\x00\\x09\n\\x0d\\x1bA\\\\\\x7f\\xff")
        assertThat(escapedSourceBytes("\u202e".toByteArray())).isEqualTo("\\xe2\\x80\\xae")
    }
}
