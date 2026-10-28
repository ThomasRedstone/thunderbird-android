package net.thunderbird.feature.mail.message.list.internal.ui.component.page

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.thunderbird.feature.notification.api.content.InAppNotification

/**
 * Hosts the message list content together with the platform's in-app notification presentation.
 */
@Composable
internal expect fun MessageListNotificationScaffold(
    eventFilter: (InAppNotification) -> Boolean,
    modifier: Modifier,
    content: @Composable (PaddingValues) -> Unit,
)
