package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.freedesktop.dbus.Marshalling
import org.junit.Test

class SignonProtocolTest {
    @Test
    fun `SignOn input signatures match the phone service and upstream adaptors`() {
        fun signature(type: Class<*>, name: String): String = Marshalling.getDBusType(
            type.declaredMethods.single { it.name == name }.genericParameterTypes,
        )
        assertThat(signature(SignonService::class.java, "registerNewIdentity")).isEqualTo("s")
        assertThat(signature(SignonService::class.java, "getIdentity")).isEqualTo("us")
        assertThat(signature(SignonService::class.java, "getAuthSessionObjectPath")).isEqualTo("uss")
        assertThat(signature(SignonIdentity::class.java, "store")).isEqualTo("a{sv}")
        assertThat(signature(SignonAuthSession::class.java, "process")).isEqualTo("a{sv}s")
    }
}
