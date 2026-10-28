package net.thunderbird.feature.mail.message.list.internal.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.components.ui.bolt.theme.ColorRoles
import net.thunderbird.core.common.action.SwipeAction

@Composable
internal actual fun rememberSwipeActionColorRoles(action: SwipeAction, toggled: Boolean): ColorRoles? {
    val colors = BoltTheme.colors
    return remember(action, toggled, colors) {
        // TODO: move these colours to the design system, see docs/architecture/design-system.md.
        when (action) {
            SwipeAction.None -> null

            SwipeAction.Delete, SwipeAction.Spam -> ColorRoles(
                accent = colors.error,
                onAccent = colors.onError,
                accentContainer = colors.errorContainer,
                onAccentContainer = colors.onErrorContainer,
            )

            SwipeAction.ArchiveDisabled, SwipeAction.ArchiveSetupArchiveFolder -> ColorRoles(
                accent = colors.surfaceContainer,
                onAccent = colors.onSurface,
                accentContainer = colors.surfaceContainerHigh,
                onAccentContainer = colors.onSurface,
            )

            else -> ColorRoles(
                accent = colors.primary,
                onAccent = colors.onPrimary,
                accentContainer = colors.primaryContainer,
                onAccentContainer = colors.onPrimaryContainer,
            )
        }
    }
}
