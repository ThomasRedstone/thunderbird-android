package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test

class ServerMailQueryTest {
    @Test
    fun `server query rejects controls blank text and excessive work before transport`() {
        listOf("", "   ", "literal\r\nUID SEARCH ALL", "nul\u0000", "x".repeat(513)).forEach { text ->
            assertThat(runCatching { ServerMailQuery(text) }.isFailure).isTrue()
        }
        listOf(0, 201).forEach { limit ->
            assertThat(runCatching { ServerMailQuery("fixture", limit = limit) }.isFailure).isTrue()
        }
        val testSubject = ServerMailQuery("quoted \"Café\"", limit = 200)
        assertThat(testSubject.toString()).isEqualTo("ServerMailQuery(redacted)")
    }

    @Test
    fun `server UID validation rejects invalid identities even for a single match`() {
        listOf("0", "-1", "+1", "01", "١", "4294967296", "not-a-uid", "9999999999999999999999").forEach { uid ->
            assertThat(runCatching { serverSearchUid(uid) }.isFailure).isTrue()
        }
        assertThat(serverSearchUid("4294967295")).isEqualTo(4294967295L)
    }
}
