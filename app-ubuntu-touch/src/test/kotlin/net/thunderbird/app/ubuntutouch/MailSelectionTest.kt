package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailSelectionTest {
    private val first = AccountIdFactory.create()
    private val second = AccountIdFactory.create()
    private val message = MailSummary("1", "Subject", "Sender", false)
    private val inbox = MailFolder("INBOX", "Inbox")
    private val archive = MailFolder("Archive", "Archive")

    @Test
    fun `selection preserves account folder UID identity and rejects foreign rows`() {
        val hits = listOf(
            CachedMailHit(first, inbox, message),
            CachedMailHit(second, inbox, message),
            CachedMailHit(first, archive, message),
        )
        var testSubject = LiveMailState(
            emptyList(),
            first,
            folder = inbox,
            searchScope = CachedSearchScope.ALL_ACCOUNTS,
            searchHits = hits,
        )
            .updateSelection(MailSelectionAction.Start).updateSelection(MailSelectionAction.All)
        assertThat(testSubject.selection.keys.size).isEqualTo(3)
        val key = MailMessageKey(first, "Archive", "1")
        testSubject = testSubject.updateSelection(MailSelectionAction.Toggle(key))
        assertThat(key in testSubject.selection.keys).isEqualTo(false)
        assertThat(testSubject.selection.keys.size).isEqualTo(2)
        assertThat(
            runCatching {
                testSubject.updateSelection(MailSelectionAction.Toggle(key.copy(uid = "other")))
            }.isFailure,
        ).isTrue()
        assertThat(testSubject.copy(searchHits = hits.take(1)).retainListedSelection().selection.keys)
            .isEqualTo(setOf(MailMessageKey(first, "INBOX", "1")))
        assertThat(testSubject.forMailEvent(LiveMailEvent.Search("changed")).selection).isEqualTo(MailSelection())
    }

    @Test
    fun `selection limit is explicit and clearing ends selection mode`() {
        val testSubject = LiveMailState(
            emptyList(),
            first,
            folder = inbox,
            messages = (0..MAX_MAIL_SELECTION).map { message.copy(id = it.toString()) },
        )
            .updateSelection(MailSelectionAction.Start).updateSelection(MailSelectionAction.All)
        assertThat(testSubject.selection.keys.size).isEqualTo(MAX_MAIL_SELECTION)
        assertThat(testSubject.notice).isEqualTo("Select up to 1000 listed messages at a time.")
        assertThat(testSubject.updateSelection(MailSelectionAction.Clear).selection).isEqualTo(MailSelection())
        assertThat(MailMessageKey(first, "INBOX", "1").toString()).isEqualTo("MailMessageKey(redacted)")
        assertThat(testSubject.selection.toString()).isEqualTo("MailSelection(redacted)")
    }
}
