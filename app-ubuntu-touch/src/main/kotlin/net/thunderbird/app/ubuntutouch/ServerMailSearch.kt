package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.store.imap.FetchListener
import com.fsck.k9.mail.store.imap.ImapMessage
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.OpenMode
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal data class ServerMailQuery(val text: String, val fullText: Boolean = false, val limit: Int = 100) {
    init {
        require(text.isNotBlank() && text.length <= MAX_SEARCH_LENGTH && text.none(Char::isISOControl))
        require(limit in 1..MAX_SERVER_SEARCH_RESULTS)
    }
    override fun toString(): String = "ServerMailQuery(redacted)"
}

internal data class ServerMailResults(
    val account: AccountId,
    val folder: String,
    val generation: Long,
    val binding: String,
    val messages: List<MailSummary>,
    val matched: Int,
) {
    val limited: Boolean get() = matched > messages.size
    override fun toString(): String = "ServerMailResults(redacted)"
}

private const val MAX_SERVER_SEARCH_RESULTS = 200
private const val MAX_IMAP_UID = 4294967295L

/** Read-only server query and bounded envelope fetch. Never marks a cache folder synchronized. */
internal class ServerFolderSearch(
    private val imap: ImapStore,
    private val resources: MailSessionResources,
    private val operation: MailOperation?,
    private val handoff: AccountHandoff,
) {
    fun search(folder: String, query: ServerMailQuery): ServerMailResults {
        active()
        val remote = imap.getFolder(folder)
        var envelopes: List<ImapMessage> = emptyList()
        try {
            remote.open(OpenMode.READ_ONLY)
            val generation = checkNotNull(remote.getUidValidity())
            check(generation in 1..MAX_IMAP_UID)
            handoff.authorize { active() }
            val matches = remote.search(query.text, null, setOf(Flag.DELETED), query.fullText)
                .distinctBy { it.uid }
                .onEach {
                    active()
                    serverSearchUid(it.uid)
                }
                .sortedByDescending {
                    active()
                    it.uid.toLong()
                }
            active()
            val selected = matches.take(query.limit)
            envelopes = selected
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
            }
            val fetched = mutableSetOf<String>()
            val listener = object : FetchListener {
                override suspend fun onFetchResponse(message: ImapMessage, isFirstResponse: Boolean) {
                    active()
                    fetched += message.uid
                }
            }
            if (selected.isNotEmpty()) runBlocking { remote.fetch(selected, profile, listener, 0) }
            active()
            check(remote.getUidValidity() == generation)
            handoff.authorize { active() }
            return ServerMailResults(
                handoff.account,
                folder,
                generation,
                handoff.binding,
                selected.filter { it.uid in fetched && Flag.DELETED !in it.flags }.map { message ->
                    MailSummary(
                        message.uid,
                        message.subject.orEmpty(),
                        message.from?.joinToString { it.toString() }.orEmpty(),
                        Flag.SEEN !in message.flags,
                        Flag.FLAGGED in message.flags,
                        (message.sentDate ?: message.internalDate)?.time,
                    )
                },
                matches.size,
            )
        } finally {
            closeMailResources(
                envelopes.map { message -> AutoCloseable { closeOwnedMimeResources(message) } } +
                    AutoCloseable { remote.close() },
            )
        }
    }

    private fun active() {
        resources.ensureActive()
        operation?.ensureActive()
    }
}

internal fun serverSearchUid(value: String): Long = checkNotNull(value.toLongOrNull()).also {
    check(it in 1..MAX_IMAP_UID && value == it.toString())
}
