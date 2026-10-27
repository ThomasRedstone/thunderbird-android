package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeUtility
import java.io.File
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.common.mail.Flag
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentBackendStorageTest {
    @get:Rule val directory = TemporaryFolder()
    private var previousTempDirectory: File? = null

    @Before
    fun prepareMimeDirectory() {
        previousTempDirectory = BinaryTempFileBody.getTempDirectory()
        BinaryTempFileBody.setTempDirectory(directory.root)
    }

    @After
    fun restoreMimeDirectory() {
        BinaryTempFileBody.setTempDirectory(previousTempDirectory)
    }

    @Test
    fun `MIME flags folder identity and sync state survive reopen`() = runTest {
        val path = directory.root.toPath().resolve("mail.sqlite")
        BinaryTempFileBody.setTempDirectory(directory.root)
        val originalId = MailDatabase(path).use { database ->
            val testSubject = PersistentBackendStorage(database, "account-a")
            testSubject.createFolderUpdater().use { it.createFolders(listOf(inbox)) }
            testSubject.setExtraString("prefix", "INBOX.")
            val folder = testSubject.getFolder("INBOX")
            folder.saveMessage(message("7"), MessageDownloadState.FULL)
            folder.setMessageFlag("7", Flag.SEEN, true)
            folder.setFolderExtraNumber("uidValidity", 42)
            folder.setFolderExtraString("sync-token", "checkpoint")
            folder.setMoreMessages(MoreMessages.TRUE)
            folder.id
        }

        MailDatabase(path).use { database ->
            val testSubject = PersistentBackendStorage(database, "account-a")
            val folder = testSubject.getFolder("INBOX")
            assertThat(folder.id).isEqualTo(originalId)
            assertThat(folder.name).isEqualTo("Inbox")
            assertThat(testSubject.getExtraString("prefix")).isEqualTo("INBOX.")
            assertThat(folder.getMoreMessages()).isEqualTo(MoreMessages.TRUE)
            assertThat(folder.getFolderExtraNumber("uidValidity")).isEqualTo(42L)
            assertThat(folder.getFolderExtraString("sync-token")).isEqualTo("checkpoint")
            assertThat(folder.getMessageFlags("7")).contains(Flag.SEEN)
            assertThat(folder.getMessageFlags("7")).contains(Flag.X_DOWNLOADED_FULL)
            assertThat(folder.readMessage("7")?.subject).isEqualTo("Persistent café")
            assertThat(folder.readMessage("7")?.uid).isEqualTo("7")
            val body = MimeUtility.findFirstPartByMimeType(folder.readMessage("7")!!, "text/plain")
            assertThat(MessageExtractor.getTextFromPart(body).trim()).isEqualTo("Cached message body.")
            assertThat(folder.getOldestMessageDate()).isNotNull()
        }
    }

    @Test
    fun `same server identifiers are isolated across accounts and deletes cascade`() = runTest {
        BinaryTempFileBody.setTempDirectory(directory.root)
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val testSubject = PersistentBackendStorage(database, "account-a")
            val otherAccount = PersistentBackendStorage(database, "account-b")
            listOf(testSubject, otherAccount).forEach {
                it.createFolderUpdater().use { updater -> updater.createFolders(listOf(inbox)) }
                it.getFolder("INBOX").saveMessage(message("7"), MessageDownloadState.FULL)
            }
            val first = testSubject.getFolder("INBOX")
            val second = otherAccount.getFolder("INBOX")
            assertThat(first.id).isNotEqualTo(second.id)
            first.setMessageFlag("7", Flag.SEEN, true)
            assertThat(Flag.SEEN in second.getMessageFlags("7")).isEqualTo(false)
            testSubject.createFolderUpdater().use { it.deleteFolders(listOf("INBOX")) }
            assertThat(testSubject.getFolderServerIds()).isEmpty()
            assertThat(second.readMessage("7")).isNotNull()
            assertThat(database.query("SELECT COUNT(*) FROM messages") { getLong(0) }.single()).isEqualTo(1L)
        }
    }

    @Test
    fun `duplicate folder rolls back entire batch and checkpoints may be cleared`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val testSubject = PersistentBackendStorage(database, "account-a")
            val failure = runCatching {
                testSubject.createFolderUpdater().use { it.createFolders(listOf(inbox, inbox)) }
            }
            assertThat(failure.isFailure).isTrue()
            assertThat(testSubject.getFolderServerIds()).isEmpty()
            testSubject.createFolderUpdater().use { it.createFolders(listOf(inbox)) }
            val folder = testSubject.getFolder("INBOX")
            folder.setFolderExtraString("token", "value")
            folder.setFolderExtraString("token", null)
            assertThat(folder.getFolderExtraString("token")).isNull()
        }
    }

    @Test
    fun `future schema is rejected without changing file`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        MailDatabase(path).use { it.execute("PRAGMA user_version=99") }
        val before = path.toFile().readBytes()
        assertThat(runCatching { MailDatabase(path).close() }.isFailure).isTrue()
        assertThat(path.toFile().readBytes().contentEquals(before)).isTrue()
    }

    @Test
    fun `corrupt database is rejected without deleting or resetting it`() {
        val path = directory.newFile("mail.sqlite").toPath()
        val corrupt = "not a database".repeat(100).toByteArray()
        path.toFile().writeBytes(corrupt)
        assertThat(runCatching { MailDatabase(path).close() }.isFailure).isTrue()
        assertThat(path.toFile().readBytes().contentEquals(corrupt)).isTrue()
    }

    @Test
    fun `protocol failure details are not stored in plaintext`() {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            val storage = PersistentBackendStorage(database, "fixture")
            storage.createFolderUpdater().use { it.createFolders(listOf(inbox)) }
            val testSubject = storage.getFolder("INBOX")
            testSubject.setStatus("untrusted reflected credential fixture")
            val status = database.query(
                "SELECT text_value FROM metadata WHERE folder=? AND name='status'",
                testSubject.id,
            ) {
                getText(0)
            }.single()
            assertThat(status).isEqualTo("failed")
        }
    }

    private fun message(uid: String): MimeMessage = MimeMessage.parseMimeMessage(
        """From: sender@example.invalid
To: recipient@example.invalid
Subject: Persistent café
Date: Tue, 27 Oct 2026 01:00:00 +0000
Content-Type: text/plain; charset=utf-8

Cached message body.
""".replace("\n", "\r\n").byteInputStream(),
        true,
    ).apply { this.uid = uid }

    private val inbox = FolderInfo("INBOX", "Inbox", FolderType.INBOX)
}
