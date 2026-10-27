package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.freedesktop.dbus.Marshalling
import org.junit.Test

class MaliitProtocolTest {
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
