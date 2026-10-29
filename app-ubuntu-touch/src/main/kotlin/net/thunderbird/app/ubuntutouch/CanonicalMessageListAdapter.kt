package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import net.thunderbird.core.common.action.SwipeAction
import net.thunderbird.core.common.action.SwipeActions
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListDateTimeFormat
import net.thunderbird.core.preference.display.visualSettings.message.list.UiDensity
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.message.list.domain.model.SortCriteria
import net.thunderbird.feature.mail.message.list.domain.model.SortType
import net.thunderbird.feature.mail.message.list.preferences.MessageListPreferences
import net.thunderbird.feature.mail.message.list.ui.state.Account
import net.thunderbird.feature.mail.message.list.ui.state.ComposedAddressStyle
import net.thunderbird.feature.mail.message.list.ui.state.ComposedAddressUi
import net.thunderbird.feature.mail.message.list.ui.state.Folder
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi
import net.thunderbird.feature.mail.message.list.ui.state.MessageListFooter
import net.thunderbird.feature.mail.message.list.ui.state.MessageListMetadata
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState
import net.thunderbird.feature.mail.message.list.ui.state.PaginationUi

/** Projects the existing protected cache's current folder into the canonical mobile list model. */
internal fun LiveMailState.canonicalMessageList(): MessageListState.LoadedMessages? {
    val context = account?.let { owner -> folder?.let { owner to it } }
    if (context == null || content != null || searchAllFolders) return null
    val (accountId, mailFolder) = context
    val owner = Account(accountId, Color.Unspecified)
    val selected = selection.keys
    val items = messages.map { summary ->
        MessageItemUi(
            state = if (summary.unread) MessageItemUi.State.Unread else MessageItemUi.State.Read,
            id = summary.id,
            messageReference = summary.id,
            account = owner,
            senders = ComposedAddressUi(
                displayName = summary.sender,
                displayNameStyles = if (summary.unread) {
                    ComposedAddressStyle.AllBold
                } else {
                    persistentListOf(ComposedAddressStyle.Regular(0))
                },
            ),
            subject = summary.subject,
            excerpt = "",
            formattedReceivedAt = summary.effectiveDate?.let(::formatMessageDate).orEmpty(),
            hasAttachments = false,
            starred = summary.starred,
            encrypted = false,
            answered = false,
            forwarded = false,
            selected = MailMessageKey(accountId, mailFolder.id, summary.id) in selected,
        )
    }.toImmutableList()
    val criteria = SortCriteria(
        sort.toCanonicalSortType(),
        if (sort in setOf(MailSort.NEWEST, MailSort.OLDEST)) null else SortType.DateDesc,
    )
    return MessageListState.LoadedMessages(
        metadata = MessageListMetadata(
            folder = Folder(mailFolder.id, owner, mailFolder.name, FolderType.REGULAR),
            swipeActions = persistentMapOf(accountId to SwipeActions(SwipeAction.None, SwipeAction.None)),
            sortCriteriaPerAccount = persistentMapOf(accountId to criteria),
            isActive = true,
            footer = MessageListFooter(showFooter = false),
            paging = PaginationUi(endReached = true),
        ),
        preferences = MessageListPreferences(
            density = UiDensity.Default,
            groupConversations = false,
            showCorrespondentNames = true,
            showMessageAvatar = false,
            showFavouriteButton = true,
            senderAboveSubject = true,
            excerptLines = 0,
            dateTimeFormat = MessageListDateTimeFormat.Contextual,
        ),
        messages = items,
    )
}

private fun MailSort.toCanonicalSortType(): SortType = when (this) {
    MailSort.NEWEST -> SortType.DateDesc
    MailSort.OLDEST -> SortType.DateAsc
    MailSort.SUBJECT -> SortType.SubjectAsc
    MailSort.SENDER -> SortType.SenderAsc
    MailSort.UNREAD -> SortType.UnreadDesc
    MailSort.STARRED -> SortType.FlaggedDesc
}

private fun formatMessageDate(timestamp: Long): String = DateTimeFormatter.ofPattern("d MMM")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(timestamp))
