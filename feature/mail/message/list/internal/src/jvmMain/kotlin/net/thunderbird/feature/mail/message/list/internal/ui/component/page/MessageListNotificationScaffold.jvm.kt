package net.thunderbird.feature.mail.message.list.internal.ui.component.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.thunderbird.feature.notification.api.content.InAppNotification

/**
 * The JVM notification platform is still empty, so no in-app notifications are presented here.
 */
@Composable
internal actual fun MessageListNotificationScaffold(
    eventFilter: (InAppNotification) -> Boolean,
    modifier: Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier) { content(PaddingValues(0.dp)) }
}
