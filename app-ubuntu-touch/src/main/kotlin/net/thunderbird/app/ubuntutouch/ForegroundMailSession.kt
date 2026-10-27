package net.thunderbird.app.ubuntutouch

import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.backend.imap.ImapPushConfigProvider
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.MimeMessage
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
import java.io.OutputStream
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
    private val oauth: OAuthMailAuthentication? = null,
) {
    init {
        initializeMailRuntime(logger, mimeDirectory)
    }

    fun open(id: AccountId): ForegroundMailSession = open(id, null)

    @Suppress("TooGenericExceptionCaught") // Release owned resources on every construction failure; rethrow unchanged.
    fun open(id: AccountId, operation: MailOperation?): ForegroundMailSession {
        val ownedSockets = CancellableSocketFactory(sockets)
        val resources = MailSessionResources(ownedSockets)
        try {
            operation?.register(resources)
            operation?.ensureActive()
            val account = checkNotNull(accounts.findById(id)) { "Account not found" }
            accounts.registerSession(account, resources)
            resources.own(AutoCloseable { accounts.releaseSession(resources) })
            val authentication = if (account.isOAuth) {
                checkNotNull(oauth) { "OAuth authentication unavailable" }.credentials(account, resources)
            } else {
                val passwords = accounts.passwords(id)
                MailSessionAuthentication(
                    account.incoming.newPassword(passwords.incoming),
                    account.outgoing.newPassword(passwords.outgoing),
                )
            }
            operation?.ensureActive()
            resources.ensureActive()
            val binding = mailCacheBinding(account)
            val storage = PersistentBackendStorage(database, id.toString(), binding)
            val imap = ImapStore.create(
                authentication.incoming,
                PreviewImapConfig,
                ownedSockets,
                authentication.incomingProvider,
            )
            val smtp = SmtpTransport(authentication.outgoing, ownedSockets, authentication.outgoingProvider)
            val backend = ImapBackend(
                accountName = id.toString(),
                backendStorage = storage,
                imapStore = imap,
                powerManager = ForegroundOnlyPlatform,
                idleRefreshManager = ForegroundOnlyPlatform,
                pushConfigProvider = ForegroundOnlyPlatform,
                smtpTransport = smtp,
            )
            return ForegroundMailSession(
                backend,
                imap,
                smtp,
                resources,
                operation,
                moveHandoff(account, storage, binding),
            )
        } catch (failure: Throwable) {
            try {
                resources.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            } finally {
                operation?.release(resources)
            }
            throw failure
        }
    }
    private fun moveHandoff(
        account: UtMailAccount,
        storage: PersistentBackendStorage,
        binding: String,
    ) = AccountHandoff(
        account.id,
        { submit -> accounts.withCurrentAccount(account, submit) },
        { folder -> storage.getFolder(folder).setFolderExtraString(CACHE_BINDING_KEY, binding) },
    )
}

internal class ForegroundMailSession(
    private val backend: ImapBackend,
    private val imap: ImapStore,
    private val smtp: SmtpTransport,
    private val resources: MailSessionResources,
    private val operation: MailOperation?,
    private val handoff: AccountHandoff,
) : AutoCloseable {
    fun move(action: MailMove, beforeSubmit: () -> Unit) {
        require(action.account == handoff.account)
        resources.ensureActive()
        operation?.ensureActive()
        val source = imap.getFolder(action.source)
        val target = imap.getFolder(action.target)
        try {
            source.open(OpenMode.READ_WRITE)
            check(source.getUidValidity() == action.sourceValidity) { "Source mailbox changed" }
            target.open(OpenMode.READ_ONLY)
            check(target.getUidValidity() == action.targetValidity) { "Destination mailbox changed" }
            target.close()
            handoff.authorize {
                resources.ensureActive()
                operation?.ensureActive()
                beforeSubmit()
                resources.ensureActive()
                operation?.ensureActive()
                source.moveMessages(listOf(source.getMessage(action.uid)), target)
            }
        } finally {
            try {
                source.close()
            } finally {
                target.close()
            }
        }
    }

    fun checkSettings() {
        resources.ensureActive()
        imap.checkSettings()
        resources.ensureActive()
        smtp.checkSettings()
    }

    fun refreshFolders() {
        resources.ensureActive()
        backend.refreshFolderList()
    }

    fun sync(folder: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        resources.ensureActive()
        val listener = ForegroundSyncListener(onProgress)
        backend.sync(folder, SYNC_CONFIG, listener)
        listener.throwIfFailed()
        handoff.authorize {
            resources.ensureActive()
            operation?.ensureActive()
            handoff.recordSynced(folder)
        }
    }

    fun setFlag(folder: String, uid: String, flag: Flag, value: Boolean, uidValidity: Long) {
        resources.ensureActive()
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
        resources.ensureActive()
        runBlocking { backend.downloadCompleteMessage(folder, uid) }
    }

    fun saveSentCopy(
        folder: String,
        id: String,
        raw: ByteArray,
        allowAppend: Boolean,
        messageId: String,
    ): SentCopyResult {
        resources.ensureActive()
        val message = FrozenSentMessage(raw, messageId)
        operation?.ensureActive()
        val existing = backend.findByMessageId(folder, checkNotNull(message.messageId))
        return when {
            existing != null -> SentCopyResult.Saved(existing)

            !allowAppend -> SentCopyResult.Missing

            else -> {
                operation?.ensureActive()
                message.uid = id
                message.setFlag(Flag.SEEN, true)
                // A null UID can still mean a successful, acknowledged APPEND without UIDPLUS.
                SentCopyResult.Saved(backend.uploadMessage(folder, message))
            }
        }
    }

    fun send(message: Message) {
        resources.ensureActive()
        require(
            listOf(Message.RecipientType.TO, Message.RecipientType.CC, Message.RecipientType.BCC).any {
                message.getRecipients(it).isNotEmpty()
            },
        ) { "At least one recipient is required" }
        backend.sendMessage(message)
    }

    override fun close() {
        try {
            resources.close()
        } finally {
            operation?.release(resources)
            imap.closeAllConnections()
        }
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

/** APPEND counts then writes the body. Preserve frozen MIME for both passes, including the owner's private Bcc. */
private class FrozenSentMessage(raw: ByteArray, id: String) : MimeMessage() {
    private val frozen = raw.copyOf()
    init {
        messageId = id
    }

    // This raw writer is private to Sent filing: it must never be used for SMTP's Bcc-stripped delivery.
    override fun writeTo(out: OutputStream) {
        out.write(frozen)
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
