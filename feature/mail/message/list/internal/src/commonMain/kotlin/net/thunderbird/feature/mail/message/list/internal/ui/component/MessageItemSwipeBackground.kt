package net.thunderbird.feature.mail.message.list.internal.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextLabelLarge
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.components.ui.bolt.theme.ColorRoles
import net.thunderbird.core.common.action.SwipeAction
import net.thunderbird.feature.mail.message.list.internal.ui.swipeActionLabel

/**
 * Displays the background content for a swipeable message list item, showing an icon and label
 * representing the swipe action being performed.
 *
 * @param action The swipe action to display, determining the icon, label, and color scheme.
 * @param toggled Whether the action is in a toggled state, which may affect the displayed icon and label
 *  for actions like ToggleRead or ToggleStar.
 * @param arrangement The horizontal arrangement of the icon and label within the surface, either Start or End.
 * @param modifier Optional modifier to be applied to the root Surface.
 */
@Composable
internal fun MessageItemSwipeBackground(
    action: SwipeAction,
    toggled: Boolean,
    arrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
) {
    val colorRoles = rememberSwipeActionColorRoles(action, toggled) ?: return
    val iconVector = remember(action, toggled) { action.iconVector(toggled) }
    val actionName = swipeActionLabel(action, toggled) ?: return

    Surface(
        modifier = modifier,
        color = colorRoles.accent,
        contentColor = colorRoles.onAccent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BoltTheme.spacings.triple),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = arrangement,
        ) {
            val movableIcon = remember(iconVector) {
                movableContentOf {
                    iconVector?.let { iconVector -> Icon(iconVector) }
                }
            }
            if (arrangement == Arrangement.Start) {
                movableIcon()
                Spacer(modifier = Modifier.width(BoltTheme.spacings.default))
            }
            TextLabelLarge(actionName)
            if (arrangement == Arrangement.End) {
                Spacer(modifier = Modifier.width(BoltTheme.spacings.default))
                movableIcon()
            }
        }
    }
}

/**
 * Resolves the [ColorRoles] used by the background of a swipe [action], or `null` when nothing should be drawn.
 */
@Composable
internal expect fun rememberSwipeActionColorRoles(action: SwipeAction, toggled: Boolean): ColorRoles?

private fun SwipeAction.iconVector(toggled: Boolean): ImageVector? = when (this) {
    SwipeAction.None -> null
    SwipeAction.ToggleSelection -> Icons.Outlined.CheckCircle
    SwipeAction.ToggleRead if toggled -> Icons.Outlined.MarkEmailUnread
    SwipeAction.ToggleRead -> Icons.Outlined.MarkEmailRead
    SwipeAction.ToggleStar if toggled -> Icons.Outlined.Star
    SwipeAction.ToggleStar -> Icons.Filled.Star
    SwipeAction.ArchiveDisabled -> null
    SwipeAction.Archive, SwipeAction.ArchiveSetupArchiveFolder -> Icons.Outlined.Archive
    SwipeAction.Delete -> Icons.Outlined.Delete
    SwipeAction.Spam -> Icons.Outlined.Report
    SwipeAction.Move -> Icons.Outlined.DriveFileMove
}
