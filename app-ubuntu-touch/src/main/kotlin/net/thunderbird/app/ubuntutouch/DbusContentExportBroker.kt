package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

internal class ContentHubUnavailable : Exception("File transfer is unavailable")
internal data class ContentHubChange(val reference: ExportTransferReference, val state: ExportBrokerState)

/** Dedicated connection. Blocking calls belong on IO; signal callbacks only enqueue bounded, authenticated events. */
internal class DbusContentExportBroker(
    private val appId: String,
    private val exportRoot: Path,
    connect: () -> DBusConnection = ::contentHubConnection,
    label: () -> String = { Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000') },
) : ContentExportBroker, AutoCloseable {
    private val prefix = contentHubExportPrefix(appId, label())
    private val connection = hubCall(connect)
    private val bus = contentHubBus(connection)
    private val closed = AtomicBoolean(false)
    private var selectionOwner: String? = null
    private var peers = emptyList<ExportDestination>()
    private val subscriptions = mutableMapOf<ExportTransferReference, AutoCloseable>()
    private val mutableChanges = MutableSharedFlow<ContentHubChange>(
        extraBufferCapacity = SIGNAL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes = mutableChanges.asSharedFlow()

    @Synchronized
    override fun destinations(): List<ExportDestination> = hubCall {
        val owner = owner()
        val service = connection.getRemoteObject(owner, "/", ContentHubServiceWire::class.java)
        val reply = service.knownDestinationsForType("all")
        require(reply.size <= MAX_PEERS)
        val received = reply.mapNotNull(::contentHubDestination)
        require(received.map { it.id }.distinct().size == received.size)
        check(owner() == owner)
        selectionOwner = owner
        peers = received.toList()
        peers.toList()
    }

    @Synchronized
    override fun create(destination: ExportDestination): ExportTransferReference = hubCall {
        val owner = checkNotNull(selectionOwner)
        check(destination in peers && owner() == owner)
        val service = connection.getRemoteObject(owner, "/", ContentHubServiceWire::class.java)
        val path = service.createExportToPeer(destination.id, appId, "all").path
        val reference = ExportTransferReference(owner, path)
        check(path.startsWith(prefix) && path.removePrefix(prefix).all { it.isDigit() })
        check(owner() == owner)
        subscribe(reference)
        reference
    }

    @Synchronized
    override fun start(reference: ExportTransferReference) = hubCall { transfer(reference).start() }

    @Synchronized
    override fun charge(reference: ExportTransferReference, file: Path) = hubCall {
        val remote = transfer(reference)
        require(file.isAbsolute && file.normalize() == file && file.parent.parent == exportRoot)
        check(Files.isDirectory(exportRoot, LinkOption.NOFOLLOW_LINKS))
        check(
            Files.isDirectory(file.parent, LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
        )
        check(file.parent.toRealPath().parent == exportRoot.toRealPath())
        require(file.fileName.toString() == "message.eml")
        require(Regex("attachment-[A-Za-z0-9_-]{1,100}").matches(file.parent.fileName.toString()))
        val item = ContentHubFileItem("", byteArrayOf(), "message.eml", file.toUri().toASCIIString())
        remote.charge(listOf(Variant(item, "(sayss)")))
    }

    @Synchronized
    override fun state(reference: ExportTransferReference): ExportBrokerState = hubCall {
        contentHubState(transfer(reference).state()) ?: throw ContentHubUnavailable()
    }

    @Synchronized
    override fun abort(reference: ExportTransferReference) = hubCall { transfer(reference).abort() }

    override fun forget(reference: ExportTransferReference) {
        val subscription = synchronized(subscriptions) { subscriptions.remove(reference) }
        subscription?.close()
    }

    /** Close can interrupt an IO call; it never waits for this object's synchronized call monitor. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection.close() }
        synchronized(subscriptions) {
            subscriptions.values.forEach { runCatching { it.close() } }
            subscriptions.clear()
        }
    }

    private fun owner(): String {
        check(!closed.get())
        if (!bus.NameHasOwner(CONTENT_HUB_SERVICE)) bus.StartServiceByName(CONTENT_HUB_SERVICE, UInt32(0))
        return bus.GetNameOwner(CONTENT_HUB_SERVICE)
    }

    private fun transfer(reference: ExportTransferReference): ContentHubTransferWire {
        check(reference.path.startsWith(prefix) && owner() == reference.owner)
        return connection.getRemoteObject(reference.owner, reference.path, ContentHubTransferWire::class.java)
    }

    private fun subscribe(reference: ExportTransferReference) {
        val remote = transfer(reference)
        val subscription = connection.addSigHandler(
            ContentHubTransferWire.StateChanged::class.java,
            reference.owner,
            remote,
        ) { signal ->
            if (!closed.get() && signal.source == reference.owner && signal.path == reference.path) {
                contentHubState(signal.state)?.let { mutableChanges.tryEmit(ContentHubChange(reference, it)) }
            }
        }
        synchronized(subscriptions) {
            if (closed.get()) subscription.close() else subscriptions[reference] = subscription
        }
    }
}

private fun contentHubBus(connection: DBusConnection): DBus = try {
    connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
} catch (_: Exception) {
    runCatching { connection.close() }
    throw ContentHubUnavailable()
}

private fun contentHubConnection(): DBusConnection {
    val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
    builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
    return builder.build()
}

private inline fun <T> hubCall(work: () -> T): T = try {
    work()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw ContentHubUnavailable()
}

private const val MAX_PEERS = 1024
private const val SIGNAL_CAPACITY = 16
