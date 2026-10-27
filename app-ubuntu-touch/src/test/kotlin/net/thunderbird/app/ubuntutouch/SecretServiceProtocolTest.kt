package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.freedesktop.dbus.Marshalling
import org.junit.Test

class SecretServiceProtocolTest {
    @Test
    fun `secret service input signatures match specification`() {
        fun signature(type: Class<*>, name: String): String = Marshalling.getDBusType(
            type.declaredMethods.single { it.name == name }.genericParameterTypes,
        )
        assertThat(signature(SecretService::class.java, "openSession")).isEqualTo("sv")
        assertThat(signature(SecretService::class.java, "searchItems")).isEqualTo("a{ss}")
        assertThat(signature(SecretCollection::class.java, "createItem")).isEqualTo("a{sv}(oayays)b")
        assertThat(signature(SecretItem::class.java, "getSecret")).isEqualTo("o")
    }
}
