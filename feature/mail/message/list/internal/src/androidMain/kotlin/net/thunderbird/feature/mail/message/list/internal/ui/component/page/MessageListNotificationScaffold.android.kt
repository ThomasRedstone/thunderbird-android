package net.thunderbird.feature.mail.message.list.internal.ui.component.page

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.thunderbird.feature.notification.api.content.InAppNotification
import net.thunderbird.feature.notification.api.ui.InAppNotificationScaffold

@Composable
internal actual fun MessageListNotificationScaffold(
    eventFilter: (InAppNotification) -> Boolean,
    modifier: Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    InAppNotificationScaffold(
        eventFilter = eventFilter,
        modifier = modifier,
        content = content,
    )
}
