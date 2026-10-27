package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MailWindowLifecycleTest {
    @Test
    fun `close waits for save and duplicate requests do not exit twice`() = runTest {
        val saved = CompletableDeferred<Boolean>()
        var exits = 0
        var backgrounds = 0
        val testSubject = MailWindowLifecycle(backgroundScope)
        testSubject.register({ saved.await() }, { backgrounds++ })
        testSubject.requestClose { exits++ }
        testSubject.requestClose { exits++ }
        testSubject.background()
        runCurrent()
        assertThat(exits).isEqualTo(0)
        assertThat(backgrounds).isEqualTo(0)
        saved.complete(true)
        runCurrent()
        assertThat(exits).isEqualTo(1)
    }

    @Test
    fun `failed save prevents exit and allows retry while removed editors are ignored`() = runTest {
        var saved = false
        var exits = 0
        var backgrounds = 0
        val testSubject = MailWindowLifecycle(backgroundScope)
        testSubject.register({ saved }, { backgrounds++ })
        testSubject.register({ error("Disposed editor") }, { error("Disposed editor") }).close()
        testSubject.background()
        assertThat(backgrounds).isEqualTo(1)
        testSubject.requestClose { exits++ }
        runCurrent()
        assertThat(exits).isEqualTo(0)
        saved = true
        testSubject.requestClose { exits++ }
        runCurrent()
        assertThat(exits).isEqualTo(1)
    }
}
