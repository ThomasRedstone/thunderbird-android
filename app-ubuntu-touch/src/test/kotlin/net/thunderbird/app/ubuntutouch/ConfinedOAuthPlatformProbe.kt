package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface

/** Opt-in platform feasibility only: no provider request, browser launch, tokens or account mutations. */
object ConfinedOAuthPlatformProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args.single()} (enforce)")
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            Socket().use { client ->
                client.connect(server.localSocketAddress, 3000)
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    client.getOutputStream().write(42)
                    check(peer.getInputStream().read() == 42)
                }
            }
        }
        println("Enforcing installed JVM loopback bind/connect/accept: PASS")
        val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
        builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
        builder.build().use { connection ->
            val dispatcher = connection.getRemoteObject(
                "com.lomiri.URLDispatcher",
                "/com/lomiri/URLDispatcher",
                OAuthDispatcherProbe::class.java,
            )
            val targets = dispatcher.testUrl(
                listOf("https://accounts.google.com/", "https://login.microsoftonline.com/"),
            )
            check(targets.size == 2 && targets.all { it.isNotBlank() })
        }
        println("Enforcing dispatcher HTTPS target resolution (no launch): PASS")
    }
}

@JvmSuppressWildcards
@DBusInterfaceName("com.lomiri.URLDispatcher")
internal interface OAuthDispatcherProbe : DBusInterface {
    @DBusMemberName("TestURL")
    fun testUrl(urls: List<String>): List<String>
}
