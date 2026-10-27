package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isTrue
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class JvmMainDispatcherTest {
    @Test
    fun `production main dispatcher runs on the Swing event thread without a test override`() = runBlocking {
        val testSubject = withContext(Dispatchers.Main) { SwingUtilities.isEventDispatchThread() }
        assertThat(testSubject).isTrue()
    }
}
