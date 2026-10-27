package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.types.Variant
import org.junit.Test

class ContentHubProtocolTest {
    @Test
    fun `app scope escapes punctuation including underscores and checks confinement identity`() {
        val app = "mail-demo.thomasredstone_mail-demo_0.1.30"
        val expected = "/transfers/mail_2ddemo_2ethomasredstone_5fmail_2ddemo_5f0_2e1_2e30/export/"
        assertThat(contentHubExportPrefix(app, "$app (enforce)")).isEqualTo(expected)
        assertThat(contentHubExportPrefix(app, "unconfined")).isEqualTo(expected)
        assertThat(runCatching { contentHubExportPrefix(app, "another.app (enforce)") }.isFailure).isTrue()
        assertThat(runCatching { contentHubExportPrefix("foreign_app_1", "unconfined") }.isFailure).isTrue()
    }

    @Test
    fun `platform methods keep exact member names and signatures`() {
        fun signature(type: Class<*>, name: String): String = Marshalling.getDBusType(
            type.declaredMethods.single { it.name == name }.genericParameterTypes,
        )
        assertThat(signature(ContentHubServiceWire::class.java, "knownDestinationsForType")).isEqualTo("s")
        assertThat(signature(ContentHubServiceWire::class.java, "createExportToPeer")).isEqualTo("sss")
        assertThat(signature(ContentHubTransferWire::class.java, "charge")).isEqualTo("av")
        assertThat(
            ContentHubServiceWire::class.java.getMethod(
                "createExportToPeer",
                String::class.java,
                String::class.java,
                String::class.java,
            )
                .getAnnotation(DBusMemberName::class.java).value,
        ).isEqualTo("CreateExportToPeer")
        assertThat(Variant(ContentHubFileItem("", byteArrayOf(), "message.eml", "file:///fixture/message.eml")).sig)
            .isEqualTo("(sayss)")
    }

    @Test
    fun `unknown states and malformed peer variants are rejected`() {
        assertThat(contentHubState(6)).isEqualTo(ExportBrokerState.FINALIZED)
        assertThat(contentHubState(7)).isEqualTo(null)
        assertThat(contentHubState(-1)).isEqualTo(null)
        assertThat(runCatching { contentHubDestination(Variant("not a peer")) }.isFailure).isTrue()
    }
}
