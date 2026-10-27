package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import java.util.Date
import net.thunderbird.core.common.mail.Flag

// BackendFolder requires the complete storage interface even for a read-only demo.
@Suppress("TooManyFunctions")
internal class DemoBackendFolder(override var name: String, var type: FolderType) : BackendFolder {
    val extraStrings: MutableMap<String, String> = mutableMapOf()
    val extraNumbers: MutableMap<String, Long> = mutableMapOf()
    val messages = mutableMapOf<String, Message>()
    private val messageFlags = mutableMapOf<String, MutableSet<Flag>>()
    private var moreMessages: MoreMessages = MoreMessages.UNKNOWN
    private var status: String? = null
    private var lastChecked = 0L

    override val visibleLimit: Int = Int.MAX_VALUE

    override fun getMessageServerIds(): Set<String> {
        return messages.keys.toSet()
    }

    override fun getAllMessagesAndEffectiveDates(): Map<String, Long?> {
        return messages
            .map { (serverId, message) ->
                serverId to message.sentDate?.time
            }
            .toMap()
    }

    override fun destroyMessages(messageServerIds: List<String>) {
        for (messageServerId in messageServerIds) {
            messages.remove(messageServerId)
            messageFlags.remove(messageServerId)
        }
    }

    override fun clearAllMessages() {
        destroyMessages(messages.keys.toList())
    }

    override fun getMoreMessages(): MoreMessages = moreMessages

    override fun setMoreMessages(moreMessages: MoreMessages) {
        this.moreMessages = moreMessages
    }

    override fun setLastChecked(timestamp: Long) {
        lastChecked = timestamp
    }

    override fun setStatus(status: String?) {
        this.status = status
    }

    override fun isMessagePresent(messageServerId: String): Boolean {
        return messages[messageServerId] != null
    }

    override fun getMessageFlags(messageServerId: String): Set<Flag> {
        return messageFlags[messageServerId] ?: error("Message $messageServerId not found")
    }

    override fun setMessageFlag(messageServerId: String, flag: Flag, value: Boolean) {
        val flags = messageFlags[messageServerId] ?: error("Message $messageServerId not found")
        if (value) {
            flags.add(flag)
        } else {
            flags.remove(flag)
        }
    }

    override suspend fun saveMessage(message: Message, downloadState: MessageDownloadState) {
        val messageServerId = checkNotNull(message.uid)
        messages[messageServerId] = message
        val flags = message.flags.toMutableSet()

        when (downloadState) {
            MessageDownloadState.ENVELOPE -> Unit
            MessageDownloadState.PARTIAL -> flags.add(Flag.X_DOWNLOADED_PARTIAL)
            MessageDownloadState.FULL -> flags.add(Flag.X_DOWNLOADED_FULL)
        }

        messageFlags[messageServerId] = flags
    }

    override fun getOldestMessageDate(): Date? {
        return messages.values.mapNotNull { it.sentDate }.minOrNull()
    }

    override fun getFolderExtraString(name: String): String? = extraStrings[name]

    override fun setFolderExtraString(name: String, value: String?) {
        if (value != null) {
            extraStrings[name] = value
        } else {
            extraStrings.remove(name)
        }
    }

    override fun getFolderExtraNumber(name: String): Long? = extraNumbers[name]

    override fun setFolderExtraNumber(name: String, value: Long) {
        extraNumbers[name] = value
    }
}
