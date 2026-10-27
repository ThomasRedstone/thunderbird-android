package net.thunderbird.app.ubuntutouch

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CancellationException
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedMessageExportTest {
    @get:Rule val directory = TemporaryFolder()
    private val account = AccountIdFactory.create()
    private val raw = ByteArray(100003) { ((it * 31) xor (it ushr 8) xor (it ushr 16)).toByte() }

    @Test
    fun `export preserves every cached binary byte with a private retained lease after database close`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val exports = directory.root.toPath().resolve("exports")
        val prepared = MailDatabase(path).use { database ->
            seed(database)
            CachedMessageExport(database, exports).prepare(account, "INBOX", "1")
        }
        assertThat(prepared.displayName).isEqualTo("message.eml")
        assertThat(prepared.mimeType).isEqualTo("message/rfc822")
        assertThat(prepared.sizeBytes).isEqualTo(raw.size.toLong())
        assertThat(Files.readAllBytes(prepared.file).toList()).isEqualTo(raw.toList())
        assertThat(Files.getPosixFilePermissions(prepared.file)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(
            Files.getPosixFilePermissions(prepared.file.parent),
        ).isEqualTo(PosixFilePermissions.fromString("rwx------"))
        prepared.close()
        Files.list(exports).use { assertThat(it.count()).isEqualTo(0L) }
    }

    @Test
    fun `partial missing or foreign messages cannot create an export`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            seed(database, complete = false)
            val exports = directory.root.toPath().resolve("exports")
            val testSubject = CachedMessageExport(database, exports)
            assertThat(runCatching { testSubject.prepare(account, "INBOX", "1") }.isFailure).isTrue()
            database.execute("INSERT INTO flags(folder,uid,flag) SELECT folder,uid,'X_DOWNLOADED_FULL' FROM messages")
            listOf(
                Triple(AccountIdFactory.create(), "INBOX", "1"),
                Triple(account, "Archive", "1"),
                Triple(account, "INBOX", "missing"),
            ).forEach { (id, folder, uid) ->
                assertThat(runCatching { testSubject.prepare(id, folder, uid) }.isFailure).isTrue()
            }
            assertThat(Files.exists(exports)).isFalse()
        }
    }

    @Test
    fun `cancellation after the first page removes the provisional export and preserves cached data`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            seed(database)
            val exports = directory.root.toPath().resolve("exports")
            var checks = 0
            val testSubject = CachedMessageExport(database, exports)
            val result = runCatching {
                testSubject.prepare(account, "INBOX", "1") {
                    if (++checks == 3) throw CancellationException("Synthetic cancellation")
                }
            }
            assertThat(result.exceptionOrNull() is CancellationException).isTrue()
            Files.list(exports).use { assertThat(it.count()).isEqualTo(0L) }
            assertThat(
                database.query("SELECT mime FROM messages") {
                    getBlob(0)
                }.single().toList(),
            ).isEqualTo(raw.toList())
            assertThat(database.query("PRAGMA quick_check") { getText(0) }).isEqualTo(listOf("ok"))
        }
    }

    @Test
    fun `snapshot excludes concurrent source replacement until exact export is complete`() {
        val path = directory.root.toPath().resolve("snapshot.sqlite")
        MailDatabase(path).use { database ->
            seed(database)
            val testSubject = CachedMessageExport(database, directory.root.toPath().resolve("exports"))
            BundledSQLiteDriver().open(path.toString()).use { writer ->
                var checks = 0
                testSubject.prepare(account, "INBOX", "1") {
                    if (++checks == 3) {
                        assertThat(
                            runCatching {
                                writer.prepare("UPDATE messages SET mime=x'010203'").use { it.step() }
                            }.isFailure,
                        ).isTrue()
                    }
                }.use { prepared ->
                    assertThat(Files.readAllBytes(prepared.file).toList()).isEqualTo(raw.toList())
                    writer.prepare("UPDATE messages SET mime=x'010203'").use { it.step() }
                    assertThat(database.query("SELECT mime FROM messages") { getBlob(0) }.single().toList())
                        .isEqualTo(listOf<Byte>(1, 2, 3))
                    assertThat(Files.readAllBytes(prepared.file).toList()).isEqualTo(raw.toList())
                }
            }
        }
    }

    @Test
    fun `filesystem failure preserves existing files and leaves the cache intact`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            seed(database)
            val occupied = Files.writeString(directory.root.toPath().resolve("exports"), "retained")
            val testSubject = CachedMessageExport(database, occupied)
            assertThat(runCatching { testSubject.prepare(account, "INBOX", "1") }.isFailure).isTrue()
            assertThat(Files.readString(occupied)).isEqualTo("retained")
            assertThat(
                database.query("SELECT length(mime) FROM messages") {
                    getLong(0)
                }.single(),
            ).isEqualTo(raw.size.toLong())
        }
    }

    private fun seed(database: MailDatabase, complete: Boolean = true) {
        val storage = PersistentBackendStorage(database, account.toString())
        storage.createFolderUpdater().use { it.createFolders(listOf(FolderInfo("INBOX", "Inbox", FolderType.INBOX))) }
        val folder = storage.getFolder("INBOX").id
        database.execute("INSERT INTO messages(folder,uid,mime) VALUES(?,?,?)", folder, "1", raw)
        if (complete) {
            database.execute(
                "INSERT INTO flags(folder,uid,flag) VALUES(?,?,'X_DOWNLOADED_FULL')",
                folder,
                "1",
            )
        }
    }
}
