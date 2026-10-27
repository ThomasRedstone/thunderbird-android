package net.thunderbird.app.ubuntutouch

import java.text.Normalizer
import java.util.Locale
import net.thunderbird.feature.account.AccountId

/** Literal, case-insensitive AND search. Only decoded viewable plain text is searched, never MIME attachments. */
internal fun searchCachedMail(
    repository: LiveMailRepository,
    account: AccountId,
    folder: String,
    query: String,
    checkActive: () -> Unit,
): List<MailSummary> {
    require(query.length <= MAX_SEARCH_LENGTH) { "Search is too long" }
    val terms = normalizedSearchText(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    checkActive()
    val messages = repository.messages(account, folder)
    if (terms.isEmpty()) return messages
    return messages.filter { summary ->
        checkActive()
        val headers = normalizedSearchText("${summary.subject}\n${summary.sender}")
        if (terms.all { it in headers }) {
            true
        } else {
            val content = repository.content(account, folder, summary.id)
            val body = if (content.plainText) normalizedSearchText(content.body) else ""
            terms.all { it in headers || it in body }
        }
    }.also { checkActive() }
}

internal const val MAX_SEARCH_LENGTH = 512
private fun normalizedSearchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

internal fun LiveMailRepository.search(
    account: AccountId,
    folder: String,
    query: String,
    checkActive: () -> Unit,
): List<MailSummary> = searchCachedMail(this, account, folder, query, checkActive)

internal data class CachedMailHit(val account: AccountId, val folder: MailFolder, val message: MailSummary) {
    override fun toString(): String = "CachedMailHit(redacted)"
}

/** Keep folder/UID identity together; a UID is meaningful only inside its owning mailbox. */
internal fun LiveMailRepository.searchAccount(
    account: AccountId,
    query: String,
    checkActive: () -> Unit,
): List<CachedMailHit> {
    checkActive()
    require(query.length <= MAX_SEARCH_LENGTH)
    return folders(account).flatMap { folder ->
        checkActive()
        search(account, folder.id, query, checkActive).map { CachedMailHit(account, folder, it) }
    }.also { checkActive() }
}

internal enum class CachedSearchScope { FOLDER, ACCOUNT, ALL_ACCOUNTS }

internal fun LiveMailRepository.searchAccounts(
    accounts: List<AccountId>,
    query: String,
    checkActive: () -> Unit,
): List<CachedMailHit> = accounts.distinct().flatMap { account ->
    checkActive()
    searchAccount(account, query, checkActive)
}.also { checkActive() }
