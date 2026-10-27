package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import java.net.URI
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.junit.Test

class LomiriOAuthBrowserTest {
    @Test
    fun `dispatch preserves encoded authorization request without preflight lookup`() {
        val sent = mutableListOf<String>()
        val testSubject = LomiriOAuthBrowser { sent.add(it) }
        val uri =
            URI(
                "https://login.example.invalid/?state=private&redirect_uri=http%3A%2F%2F127.0.0.1%3A9",
            )
        testSubject.open(uri)
        assertThat(sent).isEqualTo(listOf(uri.toASCIIString()))
    }

    @Test
    fun `unsafe targets never reach dispatcher`() {
        val testSubject = LomiriOAuthBrowser { error("Unsafe browser target dispatched") }
        listOf(
            "http://login.example.invalid/authorize",
            "file:///tmp/auth",
            "https://user@login.example.invalid/authorize",
            "https://login.example.invalid/authorize#state=private",
        ).forEach {
            assertThat(runCatching { testSubject.open(URI(it)) }.exceptionOrNull() is IllegalArgumentException).isTrue()
        }
    }

    @Test
    fun `dispatcher and transport errors expose no request or remote error content`() {
        listOf(DBusException("private-state"), DBusExecutionException("private-state"), IOException("private-state"))
            .forEach { failure ->
                val testSubject = LomiriOAuthBrowser { throw failure }
                val error = runCatching { testSubject.open(URI("https://login.example.invalid/?state=private-state")) }
                    .exceptionOrNull()
                assertThat(error is OAuthBrowserException).isTrue()
                assertThat(error?.message).isEqualTo("Unable to open authorization browser")
                assertThat(error?.cause).isNull()
                assertThat(error?.suppressed?.size).isEqualTo(0)
            }
    }
}
