package net.thunderbird.feature.mail.message.reader.api.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageReaderNavigationTest {
    @Test
    fun `adjacent messages follow current folder order`() {
        val messages = listOf("new", "current", "old")

        assertEquals("new", adjacentMessage(messages, "current", -1))
        assertEquals("old", adjacentMessage(messages, "current", 1))
    }

    @Test
    fun `missing message or list edge has no neighbor`() {
        val messages = listOf("first", "last")

        assertNull(adjacentMessage(messages, "missing", 1))
        assertNull(adjacentMessage(messages, "first", -1))
        assertNull(adjacentMessage(messages, "last", 1))
    }
}
