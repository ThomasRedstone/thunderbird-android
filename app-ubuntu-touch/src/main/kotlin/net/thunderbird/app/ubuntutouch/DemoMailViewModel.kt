package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DemoMailState(
    val folders: List<DemoFolder>,
    val folder: DemoFolder? = null,
    val message: DemoMessage? = null,
    val query: String = "",
)

internal sealed interface DemoMailEvent {
    data class OpenFolder(val folder: DemoFolder) : DemoMailEvent
    data class OpenMessage(val message: DemoMessage) : DemoMailEvent
    data class Search(val query: String) : DemoMailEvent
    data object Back : DemoMailEvent
}

internal class DemoMailViewModel(folders: List<DemoFolder>) {
    private val mutableState = MutableStateFlow(DemoMailState(folders))
    val state = mutableState.asStateFlow()

    fun event(event: DemoMailEvent) {
        val current = mutableState.value
        mutableState.value = when (event) {
            is DemoMailEvent.OpenFolder -> current.copy(folder = event.folder, message = null, query = "")

            is DemoMailEvent.OpenMessage -> {
                require(event.message in current.folder?.messages.orEmpty())
                current.copy(message = event.message)
            }

            is DemoMailEvent.Search -> current.copy(query = event.query)

            DemoMailEvent.Back -> if (current.message != null) {
                current.copy(message = null)
            } else {
                current.copy(folder = null, query = "")
            }
        }
    }
}
