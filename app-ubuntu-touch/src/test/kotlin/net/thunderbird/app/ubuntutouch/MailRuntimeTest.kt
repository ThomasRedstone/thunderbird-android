package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isFalse
import com.fsck.k9.mail.K9MailLib
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.components.core.logging.LoggingControl
import net.thunderbird.legacy.logging.Log
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailRuntimeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `mail logging does not evaluate content or enable protocol debugging`() {
        var emitted = false
        val testSubject = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) {
                emitted = true
            }
        })
        initializeMailRuntime(testSubject, directory.root.toPath())
        var evaluated = false
        Log.logger.error("Fixture") {
            evaluated = true
            "sensitive fixture"
        }
        K9MailLib.setDebug(true)
        K9MailLib.setDebugSensitive(true)
        assertThat(evaluated).isFalse()
        assertThat(emitted).isFalse()
        assertThat(K9MailLib.isDebug()).isFalse()
        assertThat(K9MailLib.isDebugSensitive()).isFalse()
        assertThat(Log.logger is LoggingControl).isFalse()
    }
}
