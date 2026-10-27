package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException

internal data class MoveDestination(val folder: MailFolder, val ready: Boolean) {
    override fun toString(): String = "MoveDestination(redacted)"
}
internal data class MoveView(
    val move: MailMove,
    val retained: MailMovePayloadInfo?,
    val sameConnection: Boolean,
    val accountName: String = "",
) {
    override fun toString(): String = "MoveView(redacted)"
}
internal data class MoveSource(val key: MailMessageKey, val subject: String, val verified: Boolean) {
    override fun toString(): String = "MoveSource(redacted)"
}
internal data class MoveOverview(
    val source: MoveSource? = null,
    val destinations: List<MoveDestination> = emptyList(),
    val history: List<MoveView> = emptyList(),
) {
    override fun toString(): String = "MoveOverview(redacted)"
}

internal interface MailMoveOperations {
    fun overview(source: MailMessageKey?): MoveOverview
    fun verify(source: MailMessageKey, operation: MailOperation)
    fun syncDestination(source: MailMessageKey, target: String, operation: MailOperation)
    fun enqueue(source: MailMessageKey, target: String): MailMove
    fun submit(move: MailMove, operation: MailOperation, acknowledgeDuplicateRisk: Boolean): MailMove
    fun reconcile(move: MailMove, operation: MailOperation): Boolean
    fun cancelQueued(move: MailMove)
    fun retained(move: MailMove, offset: Long): MailSourcePage
}

/** Blocking foreground operations. Merely opening history or selecting a destination never contacts a server. */
internal class PersistentMailMoveOperations(
    private val database: MailDatabase,
    private val accounts: UtAccountManager,
    private val sessions: ForegroundMailSessionFactory,
) : MailMoveOperations {
    private val moves = MailMoveStore(database)
    private val payloads = MailMovePayloadStore(database)
    private val runner = MailMoveRunner(moves, SessionMailMoveTransport(sessions))

    override fun overview(source: MailMessageKey?): MoveOverview {
        val all = accounts.findAll()
        val history = all.flatMap { account ->
            moves.list(account.id).map {
                MoveView(
                    it,
                    payloads.info(account.id, it.id),
                    it.binding == mailCacheBinding(account),
                    account.displayName,
                )
            }
        }
        val owner = source?.let { accounts.findById(it.account) }
        if (source == null || owner == null) return MoveOverview(history = history)
        val cache = PersistentBackendStorage(database, source.account.toString())
        val folders = cache.getFolderServerIds()
        val binding = mailCacheBinding(owner)
        val destinations = folders.filter { it != source.folder }.map {
            val folder = cache.getFolder(it)
            MoveDestination(MailFolder(it, folder.name), folder.getFolderExtraString(CACHE_BINDING_KEY) == binding)
        }
        val selected = if (source.folder in folders) {
            val folder = cache.getFolder(source.folder)
            if (folder.isMessagePresent(source.uid)) {
                folder.withCachedMessage(source.uid) { message ->
                    MoveSource(source, message.subject.orEmpty(), folder.messageBinding(source.uid) == binding)
                }
            } else {
                null
            }
        } else {
            null
        }
        return MoveOverview(selected, destinations, history)
    }

    override fun verify(source: MailMessageKey, operation: MailOperation) {
        sessions.open(source.account, operation).use { it.downloadMessage(source.folder, source.uid) }
    }

    override fun syncDestination(source: MailMessageKey, target: String, operation: MailOperation) {
        require(target != source.folder)
        sessions.open(source.account, operation).use { it.sync(target) }
    }

    override fun enqueue(source: MailMessageKey, target: String) = moves.enqueue(source, target)
    override fun submit(move: MailMove, operation: MailOperation, acknowledgeDuplicateRisk: Boolean) =
        runner.run(move, operation, acknowledgeDuplicateRisk)

    /** Reconciliation never submits another move, including for UNKNOWN outcomes. */
    override fun reconcile(move: MailMove, operation: MailOperation): Boolean {
        check(moves.get(move.account, move.id) == move)
        check(mailCacheBinding(checkNotNull(accounts.findById(move.account))) == move.binding)
        var complete = true
        sessions.open(move.account, operation).use { session ->
            listOf(move.source, move.target).forEach { folder ->
                operation.ensureActive()
                try {
                    session.sync(folder)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    complete = false
                }
            }
        }
        operation.ensureActive()
        if (move.state == MailMoveState.COMPLETE && payloads.info(move.account, move.id) != null) {
            val target = PersistentBackendStorage(database, move.account.toString()).getFolder(move.target)
            target.getMessageServerIds().forEach { uid ->
                if (payloads.info(move.account, move.id) != null) {
                    runCatching { payloads.releaseAfterDestinationCache(move, uid) }
                }
            }
        }
        return complete
    }

    override fun cancelQueued(move: MailMove) = moves.cancelQueued(move)
    override fun retained(move: MailMove, offset: Long) = payloads.page(move.account, move.id, offset)
}
