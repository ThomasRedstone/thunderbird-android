package net.thunderbird.app.ubuntutouch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.organism.drawer.ModalDrawerSheet
import net.thunderbird.components.ui.bolt.organism.drawer.ModalNavigationDrawer
import net.thunderbird.components.ui.bolt.organism.drawer.NavigationDrawerItem

/** Mailbox navigation backed by the same account and folder projection as the protected cache. */
@Composable
internal fun MailNavigationDrawer(
    state: LiveMailState,
    onEvent: (LiveMailEvent) -> Unit,
    onAccounts: () -> Unit,
    onCompose: () -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize(),
        drawerContent = { closeDrawer ->
            ModalDrawerSheet {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    TextTitleLarge("Accounts")
                    state.accounts.forEach { account ->
                        NavigationDrawerItem(
                            label = account.displayName,
                            selected = account.id == state.account && state.folder == null,
                            modifier = Modifier.testTag("drawer-account-${account.id}"),
                            onClick = {
                                if (!state.busy) {
                                    onEvent(LiveMailEvent.OpenAccount(account.id))
                                    closeDrawer()
                                }
                            },
                        )
                    }
                    NavigationDrawerItem("Manage accounts", false, {
                        onAccounts()
                        closeDrawer()
                    })
                    if (state.account != null) {
                        TextTitleLarge("Folders")
                        state.folders.forEach { folder ->
                            NavigationDrawerItem(
                                label = folder.name,
                                selected = folder.id == state.folder?.id,
                                modifier = Modifier.testTag("drawer-folder-${folder.id}"),
                                onClick = {
                                    if (!state.busy) {
                                        onEvent(LiveMailEvent.OpenFolder(folder))
                                        closeDrawer()
                                    }
                                },
                            )
                        }
                    }
                    NavigationDrawerItem("Write", false, {
                        onCompose()
                        closeDrawer()
                    })
                }
            }
        },
        content = content,
    )
}
