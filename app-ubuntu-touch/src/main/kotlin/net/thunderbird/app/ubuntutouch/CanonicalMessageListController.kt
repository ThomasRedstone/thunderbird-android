package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.thunderbird.core.common.state.StateMachine
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.effect.MessageListEffect
import net.thunderbird.feature.mail.message.list.ui.event.FolderEvent
import net.thunderbird.feature.mail.message.list.ui.event.MessageItemEvent
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import net.thunderbird.feature.mail.message.list.ui.state.Folder
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState

/** Connects the protected mailbox cache to the shared message-list reducer. */
internal class CanonicalMessageListController(
    private val mailbox: StateFlow<LiveMailState>,
    private val dispatchMail: (LiveMailEvent) -> Unit,
    factory: MessageListContract.StateMachineFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
    private val reducer: StateMachine<MessageListState, MessageListEvent> = factory.create(
        scope = scope,
        dispatch = { event ->
            if (event == MessageListEvent.ExitSelectionMode) {
                scope.launch {
                    if (reducer.currentStateSnapshot is MessageListState.SelectingMessages) reducer.process(event)
                }
            }
        },
        dispatchUiEffect = { _: MessageListEffect -> },
    )

    val state: StateFlow<MessageListState> = reducer.currentState

    init {
        scope.launch {
            mailbox.collect { current ->
                val snapshot = current.canonicalMessageList() ?: return@collect
                reducer.process(FolderEvent.FolderLoaded(checkNotNull(snapshot.metadata.folder)))
                reducer.process(MessageListEvent.UpdatePreferences(snapshot.preferences))
                reducer.process(MessageListEvent.SwipeActionsLoaded(snapshot.metadata.swipeActions))
                reducer.process(MessageListEvent.SortCriteriaLoaded(snapshot.metadata.sortCriteriaPerAccount))
                if (reducer.currentStateSnapshot is MessageListState.WarmingUp) {
                    reducer.process(MessageListEvent.AllConfigsReady)
                }
                if (reducer.currentStateSnapshot is MessageListState.LoadingMessages) {
                    reducer.process(MessageListEvent.UpdateLoadingProgress(1f, snapshot.messages))
                }
                reducer.process(MessageListEvent.MessagesLoaded(snapshot.messages))
            }
        }
    }

    fun event(event: MessageListEvent, sourceFolder: Folder) {
        scope.launch {
            val current = reducer.currentStateSnapshot
            val mailboxSnapshot = mailbox.value
            if (current.metadata.folder != sourceFolder ||
                mailboxSnapshot.canonicalMessageList()?.metadata?.folder != sourceFolder
            ) {
                return@launch
            }
            if (event.reduces(current)) reducer.process(event)
            handleCanonicalMessageListEvent(event, mailboxSnapshot, dispatchMail)
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/** Keep unsupported actions in the mailbox adapter, avoiding a reducer no-transition log of message data. */
private fun MessageListEvent.reduces(state: MessageListState): Boolean = when (this) {
    is MessageItemEvent.OnMessageClick ->
        state is MessageListState.LoadedMessages ||
            state is MessageListState.SelectingMessages

    is MessageItemEvent.ToggleSelectMessages ->
        state is MessageListState.LoadedMessages ||
            state is MessageListState.SelectingMessages

    MessageItemEvent.SelectAll,
    MessageItemEvent.DeselectAll,
    -> state is MessageListState.LoadedMessages || state is MessageListState.SelectingMessages

    MessageListEvent.EnterSelectionMode -> state is MessageListState.LoadedMessages

    MessageListEvent.ExitSelectionMode -> state is MessageListState.SelectingMessages

    else -> false
}

@Composable
internal fun rememberCanonicalMessageListController(
    mailbox: LiveMailViewModel,
    factory: MessageListContract.StateMachineFactory?,
): CanonicalMessageListController? {
    val controller = remember(mailbox, factory) {
        factory?.let { CanonicalMessageListController(mailbox.state, mailbox::event, it) }
    }
    DisposableEffect(controller) { onDispose { controller?.close() } }
    return controller
}

internal fun CanonicalMessageListController?.eventHandlerFor(state: MessageListState?): ((MessageListEvent) -> Unit)? {
    val folder = state?.metadata?.folder ?: return null
    return this?.let { controller -> { event -> controller.event(event, folder) } }
}
