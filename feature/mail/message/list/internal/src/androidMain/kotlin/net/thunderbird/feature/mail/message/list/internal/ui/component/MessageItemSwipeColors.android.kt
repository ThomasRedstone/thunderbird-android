package net.thunderbird.feature.mail.message.list.internal.ui.component

import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.components.ui.bolt.theme.ColorRoles
import net.thunderbird.components.ui.bolt.theme.toColorRoles
import net.thunderbird.components.ui.bolt.theme.toHarmonizedColor
import net.thunderbird.core.common.action.SwipeAction
import net.thunderbird.feature.mail.message.list.internal.R

@Composable
internal actual fun rememberSwipeActionColorRoles(action: SwipeAction, toggled: Boolean): ColorRoles? {
    val context = LocalContext.current
    val resources = LocalResources.current
    val primaryColor = BoltTheme.colors.primary
    val surfaceContainerLowest = BoltTheme.colors.surfaceContainerLowest
    return remember(action, toggled, primaryColor, surfaceContainerLowest) {
        // TODO: move these colours to use the design system whenever we have them available.
        val actionAttrColor = when (action) {
            SwipeAction.None -> return@remember null

            SwipeAction.ToggleSelection -> R.attr.messageListSwipeSelectColor

            SwipeAction.ToggleRead -> R.attr.messageListSwipeToggleReadColor

            SwipeAction.ToggleStar -> R.attr.messageListSwipeToggleStarColor

            SwipeAction.Archive -> R.attr.messageListSwipeArchiveColor

            SwipeAction.ArchiveDisabled, SwipeAction.ArchiveSetupArchiveFolder ->
                null

            SwipeAction.Delete -> R.attr.messageListSwipeDeleteColor

            SwipeAction.Spam -> R.attr.messageListSwipeSpamColor

            SwipeAction.Move -> R.attr.messageListSwipeMoveColor
        }
        val targetColor = if (actionAttrColor != null) {
            val typedValue = TypedValue()
            val found = context.theme.resolveAttribute(actionAttrColor, typedValue, true)
            val colorInt = if (found) {
                typedValue.data
            } else {
                error("Couldn't resolve attribute (${resources.getResourceName(actionAttrColor)}")
            }
            Color(colorInt)
        } else {
            surfaceContainerLowest
        }
        val harmonizedColor = primaryColor.toHarmonizedColor(targetColor)
        harmonizedColor.toColorRoles(context)
    }
}
