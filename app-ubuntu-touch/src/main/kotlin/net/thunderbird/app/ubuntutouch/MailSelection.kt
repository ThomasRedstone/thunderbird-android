package net.thunderbird.app.ubuntutouch

import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

internal const val MAX_MAIL_SELECTION = 1000

internal data class MailMessageKey(val account: AccountId, val folder: String, val uid: String) {
    override fun toString(): String = "MailMessageKey(redacted)"
}

internal data class MailSelection(val active: Boolean = false, val keys: Set<MailMessageKey> = emptySet()) {
    override fun toString(): String = "MailSelection(redacted)"
}

internal sealed interface MailSelectionAction {
    data object Start : MailSelectionAction
    data object Clear : MailSelectionAction
    data object All : MailSelectionAction
    data class Toggle(val key: MailMessageKey) : MailSelectionAction
    data class ApplyFlag(val flag: Flag, val enabled: Boolean) : MailSelectionAction
}

internal fun LiveMailState.listedMessageKeys(): List<MailMessageKey> = if (searchAllFolders) {
    searchHits.map { MailMessageKey(it.account, it.folder.id, it.message.id) }
} else {
    val owner = account
    val source = folder
    if (owner == null || source == null) emptyList() else messages.map { MailMessageKey(owner, source.id, it.id) }
}

internal fun LiveMailState.updateSelection(action: MailSelectionAction): LiveMailState {
    val listed = listedMessageKeys().toSet()
    val keys = selection.keys.intersect(listed)
    val changed = when (action) {
        MailSelectionAction.Start -> MailSelection(active = true)

        MailSelectionAction.Clear -> MailSelection()

        MailSelectionAction.All -> MailSelection(true, listed.take(MAX_MAIL_SELECTION).toSet())

        is MailSelectionAction.Toggle -> {
            require(selection.active && action.key in listed)
            if (action.key in keys) {
                MailSelection(true, keys - action.key)
            } else {
                MailSelection(true, (keys + action.key).take(MAX_MAIL_SELECTION).toSet())
            }
        }

        is MailSelectionAction.ApplyFlag -> selection
    }
    return copy(
        selection = changed,
        notice = if (listed.size > MAX_MAIL_SELECTION) {
            "Select up to $MAX_MAIL_SELECTION listed messages at a time."
        } else {
            null
        },
    )
}

internal fun LiveMailState.retainListedSelection(): LiveMailState = copy(
    selection = selection.copy(keys = selection.keys.intersect(listedMessageKeys().toSet())),
)

internal fun LiveMailState.forMailEvent(event: LiveMailEvent): LiveMailState =
    if (event is LiveMailEvent.Selection) this else copy(selection = MailSelection())

internal fun handleMailSelection(
    event: LiveMailEvent,
    current: LiveMailState,
    publish: (LiveMailState) -> Unit,
    apply: () -> Unit,
): Boolean {
    if (event !is LiveMailEvent.Selection) return false
    if (current.folder != null && current.content == null) {
        val action = event.action
        if (action is MailSelectionAction.ApplyFlag) {
            require(action.flag in setOf(Flag.SEEN, Flag.FLAGGED))
            val keys = current.selection.keys
            if (current.selection.active && keys.isNotEmpty() && current.listedMessageKeys().containsAll(keys)) apply()
        } else {
            publish(current.updateSelection(action))
        }
    }
    return true
}

internal fun LiveMailState.isMessageList(): Boolean = folder != null && content == null

internal fun LiveMailState.canDiscardAccountChanges(): Boolean =
    confirmDiscard && searchScope != CachedSearchScope.ALL_ACCOUNTS
