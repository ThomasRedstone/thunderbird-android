package net.thunderbird.app.ubuntutouch

import app.k9mail.backend.demo.DemoBackend
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import java.nio.file.Files

internal data class DemoMessage(val id: String, val subject: String, val sender: String, val body: String)
internal data class DemoFolder(val id: String, val name: String, val messages: List<DemoMessage>)

internal class DemoMailRepository {
    fun load(): List<DemoFolder> = synchronized(BinaryTempFileBody::class.java) {
        // MIME decoding needs a temp directory even for bundled attachments. No
        // Message objects escape this scope; retain only decoded display strings.
        val directory = Files.createTempDirectory("mail-demo-").toFile()
        val previousDirectory = BinaryTempFileBody.getTempDirectory()
        BinaryTempFileBody.setTempDirectory(directory)
        try {
            loadFolders()
        } finally {
            BinaryTempFileBody.setTempDirectory(previousDirectory)
            directory.deleteRecursively()
        }
    }

    private fun loadFolders(): List<DemoFolder> {
        val storage = DemoBackendStorage()
        val backend = DemoBackend(storage)
        backend.refreshFolderList()
        val config = SyncConfig(
            expungePolicy = SyncConfig.ExpungePolicy.MANUALLY,
            earliestPollDate = null,
            syncRemoteDeletions = false,
            maximumAutoDownloadMessageSize = 0,
            defaultVisibleLimit = Int.MAX_VALUE,
            syncFlags = emptySet(),
        )
        return storage.folders.map { (id, folder) ->
            backend.sync(id, config, DemoSyncListener())
            DemoFolder(
                id = id,
                name = folder.name,
                messages = folder.messages.map { (messageId, message) ->
                    val part = MimeUtility.findFirstPartByMimeType(message, "text/plain")
                    DemoMessage(
                        id = messageId,
                        subject = message.subject ?: "(No subject)",
                        sender = message.from?.joinToString { it.toString() }.orEmpty(),
                        body = part?.let { MessageExtractor.getTextFromPart(it) }
                            ?: "This message has no plain-text body. HTML viewing is not available in this preview.",
                    )
                },
            )
        }
    }
}

// All callbacks are required by the existing backend contract.
@Suppress("TooManyFunctions")
private class DemoSyncListener : SyncListener {
    override fun syncStarted(folderServerId: String) = Unit
    override fun syncAuthenticationSuccess() = Unit
    override fun syncHeadersStarted(folderServerId: String) = Unit
    override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
    override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) = Unit
    override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit
    override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) = Unit
    override fun syncRemovedMessage(folderServerId: String, messageServerId: String) = Unit
    override fun syncFlagChanged(folderServerId: String, messageServerId: String) = Unit
    override fun syncFinished(folderServerId: String) = Unit
    override fun folderStatusChanged(folderServerId: String) = Unit
    override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
        error("Unable to load the bundled demo mailbox")
    }
}
