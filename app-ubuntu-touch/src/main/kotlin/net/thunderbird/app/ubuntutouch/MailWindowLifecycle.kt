package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Window close waits for registered local draft writes; failed writes leave the editor alive. */
internal class MailWindowLifecycle(private val scope: CoroutineScope) {
    private val participants = mutableMapOf<Any, Pair<suspend () -> Boolean, () -> Unit>>()
    private var closing = false

    fun register(save: suspend () -> Boolean, background: () -> Unit): AutoCloseable {
        val key = Any()
        participants[key] = save to background
        return AutoCloseable { participants.remove(key) }
    }

    fun requestClose(exit: () -> Unit) {
        if (closing) return
        closing = true
        val saves = participants.values.map { it.first }
        scope.launch {
            try {
                if (saves.all { it() }) exit()
            } finally {
                closing = false
            }
        }
    }

    fun background() {
        if (!closing) participants.values.toList().forEach { it.second() }
    }
}
