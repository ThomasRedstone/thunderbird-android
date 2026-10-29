package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import net.thunderbird.feature.mail.message.list.preferences.MessageListPreferences
import net.thunderbird.feature.mail.message.list.ui.legacy.LegacyMessageListBridge
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi
import net.thunderbird.feature.mail.message.list.ui.state.MessageListMetadata

/** Feeds the canonical list state machine from the existing local cache reader. */
internal class CachedMessageListBridge(private val mailbox: StateFlow<LiveMailState>) : LegacyMessageListBridge {
    override fun loadMessages(
        preferences: MessageListPreferences,
        metadata: MessageListMetadata,
    ): Flow<List<MessageItemUi>> = mailbox.map { current ->
        val projection = current.canonicalMessageList()
        val requestedKey = metadata.folder?.let { it.account.id to it.id }
        val actualKey = projection?.metadata?.folder?.let { it.account.id to it.id }
        if (requestedKey != null && requestedKey == actualKey) {
            projection?.messages.orEmpty()
        } else {
            emptyList()
        }
    }
}
