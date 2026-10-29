package net.thunderbird.feature.mail.message.list.internal.ui.state.machine

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.mail.message.list.ui.event.FolderEvent
import net.thunderbird.feature.mail.message.list.ui.event.MessageListEvent
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState

@OptIn(ExperimentalCoroutinesApi::class)
class CachedFolderTransitionMessageListStateMachineTest : BaseMessageListStateMachineTest() {
    @Test
    fun `switching cached folders clears the old rows before the new rows arrive`() = runTest {
        val old = createMessageUiItem(MessageItemUi.State.Unread, "old")
        val testSubject = createStateMachineOnLoadedState(listOf(old), folder = createFolder(id = "inbox"))

        testSubject.process(FolderEvent.FolderLoaded(createFolder(id = "archive")))
        val loading = testSubject.currentStateSnapshot as MessageListState.LoadingMessages
        assertThat(loading.metadata.folder?.id).isEqualTo("archive")
        assertThat(loading.messages).isEqualTo(emptyList())

        val next = createMessageUiItem(MessageItemUi.State.Read, "new")
        testSubject.process(MessageListEvent.UpdateLoadingProgress(1f, listOf(next)))
        testSubject.process(MessageListEvent.MessagesLoaded(listOf(next)))
        val loaded = testSubject.currentStateSnapshot as MessageListState.LoadedMessages
        assertThat(loaded.metadata.folder?.id).isEqualTo("archive")
        assertThat(loaded.messages.map { it.id }).isEqualTo(listOf("new"))
    }

    @Test
    fun `cache refresh replaces rows and ends selection when none remain selected`() = runTest {
        val selected = createMessageUiItem(MessageItemUi.State.Unread, "old", selected = true)
        val testSubject = createStateMachineOnSelectingMessages(listOf(selected))

        val fresh = createMessageUiItem(MessageItemUi.State.Read, "fresh")
        testSubject.process(MessageListEvent.MessagesLoaded(listOf(fresh)))

        val loaded = testSubject.currentStateSnapshot as MessageListState.LoadedMessages
        assertThat(loaded.messages.map { it.id }).isEqualTo(listOf("fresh"))
    }
}
