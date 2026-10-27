package net.thunderbird.app.ubuntutouch

internal fun handleSourceEvent(
    event: LiveMailEvent,
    current: LiveMailState,
    publish: (LiveMailState) -> Unit,
    read: () -> Unit,
): Boolean {
    if (event is LiveMailEvent.Source) {
        if (current.content != null) read()
    } else if (current.source != null) {
        if (event == LiveMailEvent.Back) publish(current.copy(source = null, notice = null))
    } else {
        return false
    }
    return true
}

internal fun LiveMailRepository.readSource(initial: LiveMailState, action: LiveMailEvent.Source): LiveMailState =
    initial.copy(
        source = source(
            checkNotNull(initial.account),
            checkNotNull(initial.folder).id,
            checkNotNull(initial.content).summary.id,
            action.offset,
        ),
        busy = false,
    )
