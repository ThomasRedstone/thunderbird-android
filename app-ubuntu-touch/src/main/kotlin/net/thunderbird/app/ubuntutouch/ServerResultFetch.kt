package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.store.imap.FetchListener
import com.fsck.k9.mail.store.imap.ImapMessage
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.OpenMode
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.mail.Flag

/** Select and fetch on the same read-only mailbox connection, then authorize a single local save. */
internal class ServerResultFetch(
    private val imap: ImapStore,
    private val resources: MailSessionResources,
    private val operation: MailOperation?,
    private val handoff: AccountHandoff,
) {
    fun fetch(results: ServerMailResults, uid: String, save: (Message) -> Unit) {
        require(results.account == handoff.account && results.binding == handoff.binding)
        require(results.messages.any { it.id == uid })
        serverSearchUid(uid)
        active()
        val remote = imap.getFolder(results.folder)
        val message = remote.getMessage(uid)
        try {
            remote.open(OpenMode.READ_ONLY)
            check(remote.getUidValidity() == results.generation) { "Mailbox changed; search again" }
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.BODY)
            }
            var fetched = false
            val listener = object : FetchListener {
                override suspend fun onFetchResponse(message: ImapMessage, isFirstResponse: Boolean) {
                    active()
                    if (message.uid == uid) fetched = true
                }
            }
            runBlocking { remote.fetch(listOf(message), profile, listener, 0) }
            active()
            check(fetched && Flag.DELETED !in message.flags && message.body != null) {
                "Message unavailable; search again"
            }
            check(remote.getUidValidity() == results.generation) { "Mailbox changed; search again" }
            handoff.authorize {
                active()
                save(message)
            }
        } finally {
            closeMailResources(
                listOf(AutoCloseable { closeOwnedMimeResources(message) }, AutoCloseable { remote.close() }),
            )
        }
    }

    private fun active() {
        resources.ensureActive()
        operation?.ensureActive()
    }
}
