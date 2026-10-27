package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.Locale
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailSortTest {
    private val messages = listOf(
        MailSummary("b", "beta", "Alice", true, false, 100),
        MailSummary("a", "Alpha", "Zed", false, true, 200),
        MailSummary("c", "Café", "Bob", true, true, null),
    )

    @Test
    fun `all sort modes apply date header and flag priorities`() {
        val expected = mapOf(
            MailSort.NEWEST to listOf("a", "b", "c"),
            MailSort.OLDEST to listOf("b", "a", "c"),
            MailSort.SUBJECT to listOf("a", "b", "c"),
            MailSort.SENDER to listOf("b", "c", "a"),
            MailSort.UNREAD to listOf("b", "c", "a"),
            MailSort.STARRED to listOf("a", "c", "b"),
        )
        expected.forEach { (sort, ids) ->
            val testSubject = LiveMailState(emptyList(), messages = messages, sort = sort)
            assertThat(testSubject.sortedForDisplay().messages.map { it.id }).isEqualTo(ids)
            assertThat(testSubject.messages).isEqualTo(messages)
        }
    }

    @Test
    fun `dates compare without overflow and missing date remains last in both directions`() {
        val data = listOf(
            messages[0].copy(effectiveDate = Long.MIN_VALUE),
            messages[1].copy(effectiveDate = Long.MAX_VALUE),
            messages[2],
        )
        assertThat(LiveMailState(emptyList(), messages = data).sortedForDisplay().messages.map { it.id })
            .isEqualTo(listOf("a", "b", "c"))
        assertThat(
            LiveMailState(emptyList(), messages = data, sort = MailSort.OLDEST).sortedForDisplay().messages.map {
                it.id
            },
        )
            .isEqualTo(listOf("b", "a", "c"))
    }

    @Test
    fun `header ordering normalizes Unicode and is independent of default locale with stable ties`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val data = listOf(
                messages[0].copy(subject = "Café", effectiveDate = 100),
                messages[1].copy(subject = "Cafe\u0301", effectiveDate = 100),
                messages[0].copy(id = "d", subject = "I", effectiveDate = 100),
                messages[0].copy(id = "e", subject = "i", effectiveDate = 100),
            )
            val testSubject = LiveMailState(emptyList(), messages = data.reversed(), sort = MailSort.SUBJECT)
            assertThat(testSubject.sortedForDisplay().messages.map { it.id }).isEqualTo(listOf("a", "b", "d", "e"))
            assertThat(testSubject.copy(messages = data).sortedForDisplay().messages.map { it.id })
                .isEqualTo(listOf("a", "b", "d", "e"))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun `global search preserves duplicate UID identities and sorts across account and folder boundaries`() {
        val first = AccountIdFactory.of("00000000-0000-0000-0000-000000000001")
        val second = AccountIdFactory.of("00000000-0000-0000-0000-000000000002")
        val inbox = MailFolder("INBOX", "Inbox")
        val archive = MailFolder("Archive", "Archive")
        val sameUid = messages[0].copy(id = "1")
        val hits = listOf(
            CachedMailHit(second, inbox, sameUid),
            CachedMailHit(first, inbox, sameUid),
            CachedMailHit(first, archive, sameUid),
        )
        val testSubject = LiveMailState(emptyList(), searchHits = hits)
        assertThat(testSubject.sortedForDisplay().searchHits).isEqualTo(listOf(hits[2], hits[1], hits[0]))
        assertThat(testSubject.searchHits).isEqualTo(hits)
    }
}
