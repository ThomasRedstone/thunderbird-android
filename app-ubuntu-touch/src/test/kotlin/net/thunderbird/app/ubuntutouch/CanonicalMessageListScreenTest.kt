package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.list.ui.event.MessageItemEvent
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import org.junit.Test

class CanonicalMessageListScreenTest {
    private val account = AccountIdFactory.create()
    private val folder = MailFolder("INBOX", "Inbox")
    private val row = MailSummary("one", "Subject", "Sender", unread = true, starred = false)
    private val mailbox = LiveMailState(emptyList(), account = account, folder = folder, messages = listOf(row))
    private val item = checkNotNull(mailbox.canonicalMessageList()).messages.single()
    private val key = MailMessageKey(account, folder.id, row.id)

    @Test
    fun `row click opens cached message by UID`() {
        val actual = mutableListOf<LiveMailEvent>()

        handleCanonicalMessageListEvent(MessageItemEvent.OnMessageClick(item), mailbox, actual::add)

        assertThat(actual).isEqualTo(listOf(LiveMailEvent.OpenMessage("one")))
    }

    @Test
    fun `favourite uses existing queued flag action path`() {
        val actual = mutableListOf<LiveMailEvent>()

        handleCanonicalMessageListEvent(MessageItemEvent.ToggleFavourite(item), mailbox, actual::add)

        assertThat(actual).isEqualTo(
            listOf(
                LiveMailEvent.Selection(MailSelectionAction.Start),
                LiveMailEvent.Selection(MailSelectionAction.Toggle(key)),
                LiveMailEvent.Selection(MailSelectionAction.ApplyFlag(Flag.FLAGGED, true)),
            ),
        )
    }

    @Test
    fun `read action queues seen flag and selection toggles`() {
        val actual = mutableListOf<LiveMailEvent>()

        handleCanonicalMessageListEvent(MessageItemEvent.ToggleReadUnread(item), mailbox, actual::add)
        handleCanonicalMessageListEvent(MessageItemEvent.ToggleSelectMessages(item), mailbox, actual::add)

        assertThat(actual).isEqualTo(
            listOf(
                LiveMailEvent.Selection(MailSelectionAction.Start),
                LiveMailEvent.Selection(MailSelectionAction.Toggle(key)),
                LiveMailEvent.Selection(MailSelectionAction.ApplyFlag(Flag.SEEN, true)),
                LiveMailEvent.Selection(MailSelectionAction.Start),
                LiveMailEvent.Selection(MailSelectionAction.Toggle(key)),
            ),
        )
    }

    @Test
    fun `stale or foreign row cannot trigger an action`() {
        val actual = mutableListOf<LiveMailEvent>()
        val foreign = item.copy(account = item.account.copy(id = AccountIdFactory.create()))

        handleCanonicalMessageListEvent(MessageItemEvent.OnMessageClick(foreign), mailbox, actual::add)
        handleCanonicalMessageListEvent(
            MessageItemEvent.ToggleFavourite(item.copy(messageReference = "missing")),
            mailbox,
            actual::add,
        )
        handleCanonicalMessageListEvent(MessageListEvent.Refresh, mailbox.copy(busy = true), actual::add)

        assertThat(actual).isEqualTo(emptyList())
    }
}
