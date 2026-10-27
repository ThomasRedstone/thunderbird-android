package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.Composable
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge

@Composable
internal fun FolderRoleControls(state: MailMoveUiState, onEvent: (MailMoveEvent) -> Unit) {
    val role = state.role ?: return
    val configured = state.overview.roles[role]
    TextBodyLarge("${role.title} destination: ${configured ?: "Not configured"}")
    if (configured == state.request?.folder) {
        TextBodyLarge("This message is already in the configured ${role.title} folder.")
    }
    if (role == MailFolderRole.TRASH) TextBodyLarge("Moving to Trash does not permanently delete the message.")
    if (state.target != null && state.target != configured) {
        ButtonText("Use selected folder for ${role.title}", {
            onEvent(MailMoveEvent.ConfigureRole)
        }, enabled = !state.busy)
    }
    if (configured != null) {
        ButtonText("Clear ${role.title} destination", { onEvent(MailMoveEvent.ClearRole) }, enabled = !state.busy)
    }
    TextBodyLarge("The destination must be configured before queueing this action.")
}

internal fun MailMoveUiState.roleMatchesTarget(): Boolean = role == null || overview.roles[role] == target

@Composable
internal fun ReaderFolderRoleActions(
    state: LiveMailState,
    onOrganize: ((MailMessageKey, MailFolderRole) -> Unit)?,
) {
    val key = state.moveRequest() ?: return
    if (onOrganize == null) return
    MailFolderRole.entries.forEach { role ->
        ButtonText(if (role == MailFolderRole.TRASH) "Move to Trash" else role.title, {
            onOrganize(key, role)
        }, enabled = !state.busy)
    }
}
