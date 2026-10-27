package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.sun.security.auth.module.UnixSystem
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DbusContentExportBrokerTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `private bus carries selected receiver and exact file item and rejects replacement owner`() {
        val socket = directory.root.toPath().resolve("bus")
        val daemon = ProcessBuilder(
            "dbus-daemon",
            "--session",
            "--nofork",
            "--print-address=1",
            "--address=unix:path=$socket",
        )
            .redirectError(directory.newFile("daemon.log")).start()
        try {
            val address = checkNotNull(daemon.inputStream.bufferedReader().readLine())
            connection(address).use { server ->
                val fixture = WireExportFixture()
                server.exportObject("/", fixture)
                server.exportObject(TRANSFER_PATH, fixture.transfer)
                server.requestBusName(CONTENT_HUB_SERVICE)
                val root = directory.newFolder("exports space").toPath()
                DbusContentExportBroker(APP_ID, root, { connection(address) }, { "unconfined" }).use { testSubject ->
                    val destination = ExportDestination("filemanager.fixture", "Fixture receiver")
                    assertThat(runCatching { testSubject.create(destination) }.isFailure).isTrue()
                    assertThat(testSubject.destinations()).isEqualTo(listOf(destination))
                    val reference = testSubject.create(destination)
                    assertThat(fixture.arguments).isEqualTo(listOf(destination.id, APP_ID, "all"))
                    assertThat(reference.owner).isEqualTo(server.uniqueName)
                    assertThat(reference.path).isEqualTo(TRANSFER_PATH)
                    assertThat(fixture.transfer.starts).isEqualTo(0)
                    testSubject.start(reference)
                    assertThat(testSubject.state(reference)).isEqualTo(ExportBrokerState.IN_PROGRESS)
                    val file = Files.createDirectory(root.resolve("attachment-fixture")).resolve("message.eml")
                    val bytes = byteArrayOf(0, 13, 10, -1, 65)
                    Files.write(file, bytes)
                    testSubject.charge(reference, file)
                    val item = checkNotNull(fixture.transfer.item)
                    assertThat(item.sig).isEqualTo("(sayss)")
                    val fields = item.value as Array<*>
                    assertThat(fields[0]).isEqualTo("")
                    assertThat(fields[1]).isEqualTo(emptyList<Byte>())
                    assertThat(fields[2]).isEqualTo("message.eml")
                    val received = Path.of(URI(fields[3] as String))
                    assertThat(received).isEqualTo(file)
                    assertThat(Files.readAllBytes(received).contentEquals(bytes)).isTrue()
                    assertThat(testSubject.state(reference)).isEqualTo(ExportBrokerState.CHARGED)
                    verifySignals(server, address, testSubject, reference)
                    testSubject.forget(reference)
                    server.releaseBusName(CONTENT_HUB_SERVICE)
                    connection(address).use { replacement ->
                        val other = WireExportFixture()
                        replacement.exportObject("/", other)
                        replacement.exportObject(TRANSFER_PATH, other.transfer)
                        replacement.requestBusName(CONTENT_HUB_SERVICE)
                        assertThat(runCatching { testSubject.state(reference) }.isFailure).isTrue()
                        assertThat(other.transfer.reads).isEqualTo(0)
                    }
                }
            }
        } finally {
            stopDaemon(daemon)
        }
    }

    private fun verifySignals(
        server: DBusConnection,
        address: String,
        testSubject: DbusContentExportBroker,
        reference: ExportTransferReference,
    ) = runBlocking {
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { testSubject.changes.take(2).toList() }
        }
        connection(address).use { attacker ->
            attacker.sendMessage(ContentHubTransferWire.StateChanged(TRANSFER_PATH, 5))
            server.sendMessage(ContentHubTransferWire.StateChanged(TRANSFER_PATH, 3))
            server.sendMessage(ContentHubTransferWire.StateChanged(TRANSFER_PATH, 4))
            assertThat(received.await()).isEqualTo(
                listOf(
                    ContentHubChange(reference, ExportBrokerState.CHARGED),
                    ContentHubChange(reference, ExportBrokerState.COLLECTED),
                ),
            )
        }
    }

    private fun stopDaemon(daemon: Process) {
        daemon.destroy()
        if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
            daemon.destroyForcibly()
            check(daemon.waitFor(5, TimeUnit.SECONDS))
        }
    }

    private fun connection(address: String): DBusConnection {
        val builder = DBusConnectionBuilder.forAddress(address).withShared(false)
        builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
        return builder.build()
    }
}

internal class WirePeer(
    @field:Position(0) val id: String,
    @field:Position(1) val name: String,
    @field:Position(2) val icon: ByteArray,
    @field:Position(3) val iconName: String,
    @field:Position(4) val default: Boolean,
) : Struct()

internal class WireExportFixture : ContentHubServiceWire {
    var arguments = emptyList<String>()
    val transfer = WireTransferFixture()
    override fun getObjectPath(): String = "/"
    override fun knownDestinationsForType(type: String): List<Variant<*>> {
        check(type == "all")
        return listOf(Variant(WirePeer("filemanager.fixture", "Fixture receiver", byteArrayOf(), "", false)))
    }
    override fun createExportToPeer(peer: String, app: String, type: String): DBusPath {
        arguments = listOf(peer, app, type)
        return DBusPath(TRANSFER_PATH)
    }
}

internal class WireTransferFixture : ContentHubTransferWire {
    var starts = 0
    var reads = 0
    var current = 0
    var item: Variant<*>? = null
    override fun getObjectPath(): String = TRANSFER_PATH
    override fun start() {
        starts++
        current = 2
    }
    override fun state(): Int {
        reads++
        return current
    }
    override fun charge(items: List<Variant<*>>) {
        item = items.single()
        current = 3
    }
    override fun abort() {
        current = 5
    }
}

private const val APP_ID = "mail-demo.thomasredstone_mail-demo_0.1.30"
private const val TRANSFER_PATH = "/transfers/mail_2ddemo_2ethomasredstone_5fmail_2ddemo_5f0_2e1_2e30/export/42"
