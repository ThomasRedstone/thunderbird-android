package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.freedesktop.dbus.Marshalling
import org.junit.Test

class MaliitProtocolTest {
    @Test
    fun `password hints disable prediction correction and capitalization and plain hints reset them`() {
        val testSubject = maliitWidgetState(hiddenText = true).mapValues { it.value.value }
        assertThat(testSubject["hiddenText"]).isEqualTo(true)
        assertThat(testSubject["predictionEnabled"]).isEqualTo(false)
        assertThat(testSubject["correctionEnabled"]).isEqualTo(false)
        assertThat(testSubject["autocapitalizationEnabled"]).isEqualTo(false)
        val plain = maliitWidgetState(hiddenText = false).mapValues { it.value.value }
        assertThat(plain["hiddenText"]).isEqualTo(false)
        assertThat(plain["predictionEnabled"]).isEqualTo(true)
    }

    @Test
    fun `callback signatures match the working ut-platform wire protocol`() {
        val methods = MaliitInputContext::class.java.declaredMethods.associateBy { it.name }
        fun signature(name: String) = Marshalling.getDBusType(checkNotNull(methods[name]).genericParameterTypes)

        assertThat(signature("commitString")).isEqualTo("siii")
        assertThat(signature("updatePreedit")).isEqualTo("sa(iii)iii")
        assertThat(signature("keyEvent")).isEqualTo("iiisbiy")
        assertThat(signature("updateInputMethodArea")).isEqualTo("iiii")
    }
}
