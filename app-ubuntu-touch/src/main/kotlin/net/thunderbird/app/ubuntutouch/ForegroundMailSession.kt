package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.backend.imap.ImapPushConfigProvider
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mail.power.WakeLock
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleRefreshTimer
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.OpenMode
import com.fsck.k9.mail.transport.smtp.SmtpTransport
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

/** Blocking engine operations: callers must use Dispatchers.IO and close the session. No push API is exposed. */
internal class ForegroundMailSessionFactory(
    private val accounts: UtAccountManager,
    private val database: MailDatabase,
    private val sockets: TrustedSocketFactory,
    logger: Logger,
    mimeDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"), "mail-mime"),
) {
    init {
        initializeMailRuntime(logger, mimeDirectory)
    }

    fun open(id: AccountId): ForegroundMailSession = open(id, null)

    @Suppress("TooGenericExceptionCaught") // Release owned resources on every construction failure; rethrow unchanged.
    fun open(id: AccountId, operation: MailOperation?): ForegroundMailSession {
        val ownedSockets = CancellableSocketFactory(sockets)
        try {
            operation?.register(ownedSockets)
            operation?.ensureActive()
            val account = checkNotNull(accounts.findById(id)) { "Account not found" }
            val passwords = accounts.passwords(id)
            operation?.ensureActive()
            val storage = PersistentBackendStorage(database, id.toString())
            val imap = ImapStore.create(
                account.incoming.newPassword(passwords.incoming),
                PreviewImapConfig,
                ownedSockets,
                null,
            )
            val smtp = SmtpTransport(account.outgoing.newPassword(passwords.outgoing), ownedSockets, null)
            val backend = ImapBackend(
                accountName = id.toString(),
                backendStorage = storage,
                imapStore = imap,
                powerManager = ForegroundOnlyPlatform,
                idleRefreshManager = ForegroundOnlyPlatform,
                pushConfigProvider = ForegroundOnlyPlatform,
                smtpTransport = smtp,
            )
            return ForegroundMailSession(backend, imap, smtp, ownedSockets, operation)
        } catch (failure: Throwable) {
            ownedSockets.close()
            operation?.release(ownedSockets)
            throw failure
        }
    }
}

internal class ForegroundMailSession(
    private val backend: ImapBackend,
    private val imap: ImapStore,
    private val smtp: SmtpTransport,
    private val sockets: CancellableSocketFactory,
    private val operation: MailOperation?,
) : AutoCloseable {
    fun checkSettings() {
        imap.checkSettings()
        smtp.checkSettings()
    }

    fun refreshFolders() {
        backend.refreshFolderList()
    }

    fun sync(folder: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val listener = ForegroundSyncListener(onProgress)
        backend.sync(folder, SYNC_CONFIG, listener)
        listener.throwIfFailed()
    }

    fun setFlag(folder: String, uid: String, flag: Flag, value: Boolean, uidValidity: Long) {
        require(flag in setOf(Flag.SEEN, Flag.FLAGGED))
        val remote = imap.getFolder(folder)
        try {
            operation?.ensureActive()
            remote.open(OpenMode.READ_WRITE)
            check(remote.getUidValidity() == uidValidity) { "Mailbox identity changed; action retained for review" }
            operation?.ensureActive()
            remote.setFlags(listOf(remote.getMessage(uid)), setOf(flag), value)
        } finally {
            remote.close()
        }
    }

    fun downloadMessage(folder: String, uid: String) {
        runBlocking { backend.downloadCompleteMessage(folder, uid) }
    }

    fun send(message: Message) {
        require(
            listOf(Message.RecipientType.TO, Message.RecipientType.CC, Message.RecipientType.BCC).any {
                message.getRecipients(it).isNotEmpty()
            },
        ) { "At least one recipient is required" }
        backend.sendMessage(message)
    }

    override fun close() {
        sockets.close()
        operation?.release(sockets)
        imap.closeAllConnections()
    }

    private companion object {
        const val PREVIEW_VISIBLE_LIMIT = 100
        const val AUTO_DOWNLOAD_BYTES = 1024 * 1024
        val SYNC_CONFIG = SyncConfig(
            expungePolicy = SyncConfig.ExpungePolicy.MANUALLY,
            earliestPollDate = null,
            syncRemoteDeletions = true,
            maximumAutoDownloadMessageSize = AUTO_DOWNLOAD_BYTES,
            defaultVisibleLimit = PREVIEW_VISIBLE_LIMIT,
            syncFlags = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED),
        )
    }
}

private object PreviewImapConfig : ImapStoreConfig {
    override val logLabel = "UbuntuTouchMail"
    override fun isSubscribedFoldersOnly(): Boolean = false
    override fun isExpungeImmediately(): Boolean = false
    override fun clientInfo(): ImapClientInfo = ImapClientInfo("Mail preview", "0.1")
}

/** The foreground port must not silently pretend that wake locks or background push have been implemented. */
private object ForegroundOnlyPlatform : PowerManager, IdleRefreshManager, ImapPushConfigProvider {
    override fun newWakeLock(tag: String): WakeLock = error("Background mail is not configured")
    override fun startTimer(
        timeout: Long,
        callback: () -> Unit,
    ): IdleRefreshTimer = error("Background mail is not configured")
    override fun resetTimers(): Unit = error("Background mail is not configured")
    override val maxPushFoldersFlow: Flow<Int> = flowOf(0)
    override val idleRefreshMinutesFlow: Flow<Int> = flowOf(0)
}
