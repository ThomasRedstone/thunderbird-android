package net.thunderbird.app.ubuntutouch

import java.text.Normalizer
import java.util.Locale

internal enum class MailSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    SUBJECT("Subject A–Z"),
    SENDER("Sender A–Z"),
    UNREAD("Unread first"),
    STARRED("Starred first"),
}

/** Called on the cache reader's IO dispatcher; sorting neither authenticates nor changes server state. */
internal fun LiveMailState.sortedForDisplay(): LiveMailState {
    val comparator = sort.comparator()
    return copy(
        messages = messages.sortedWith(comparator),
        searchHits = searchHits.sortedWith(
            Comparator<CachedMailHit> { first, second -> comparator.compare(first.message, second.message) }
                .thenBy { it.account.toString() }.thenBy { it.folder.id },
        ),
    )
}

private fun MailSort.comparator(): Comparator<MailSummary> {
    val newest = dateComparator(oldest = false)
    val keys = mutableMapOf<MailSummary, String>()
    return when (this) {
        MailSort.NEWEST -> newest
        MailSort.OLDEST -> dateComparator(oldest = true)
        MailSort.SUBJECT -> compareBy<MailSummary> { keys.getOrPut(it) { sortText(it.subject) } }.then(newest)
        MailSort.SENDER -> compareBy<MailSummary> { keys.getOrPut(it) { sortText(it.sender) } }.then(newest)
        MailSort.UNREAD -> compareByDescending<MailSummary> { it.unread }.then(newest)
        MailSort.STARRED -> compareByDescending<MailSummary> { it.starred }.then(newest)
    }.thenBy { it.id }
}

/** Unknown dates stay last in either direction; comparisons avoid timestamp subtraction/overflow. */
private fun dateComparator(oldest: Boolean): Comparator<MailSummary> =
    compareBy<MailSummary> { it.effectiveDate == null }.thenComparator { first, second ->
        val firstDate = first.effectiveDate ?: 0L
        val secondDate = second.effectiveDate ?: 0L
        if (oldest) firstDate.compareTo(secondDate) else secondDate.compareTo(firstDate)
    }

private fun sortText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
