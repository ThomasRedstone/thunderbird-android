package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.SyncListener

@Suppress("TooManyFunctions") // The reused backend contract requires every callback.
internal class ForegroundSyncListener(private val onProgress: (Int, Int) -> Unit) : SyncListener {
    private var failed = false
    private var failure: Exception? = null
    fun throwIfFailed() {
        if (failed) throw failure ?: IllegalStateException("Mail synchronization failed")
    }
    override fun syncStarted(folderServerId: String) = onProgress(0, 0)
    override fun syncAuthenticationSuccess() = Unit
    override fun syncHeadersStarted(folderServerId: String) = Unit
    override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = onProgress(completed, total)
    override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) = Unit
    override fun syncProgress(folderServerId: String, completed: Int, total: Int) = onProgress(completed, total)
    override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) = Unit
    override fun syncRemovedMessage(folderServerId: String, messageServerId: String) = Unit
    override fun syncFlagChanged(folderServerId: String, messageServerId: String) = Unit
    override fun syncFinished(folderServerId: String) = Unit
    override fun folderStatusChanged(folderServerId: String) = Unit
    override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
        failed = true
        failure = exception
    }
}
