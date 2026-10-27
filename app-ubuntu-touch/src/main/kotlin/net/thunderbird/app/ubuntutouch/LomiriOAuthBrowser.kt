package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import java.io.IOException
import java.net.URI
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface

internal fun interface OAuthBrowser {
    /** Blocking dispatcher call; the owner must call on IO. Return means dispatched, not login completed. */
    fun open(authorizationUri: URI)
}

internal class LomiriOAuthBrowser(
    private val dispatch: (String) -> Unit = ::dispatchOAuthUri,
) : OAuthBrowser {
    override fun open(authorizationUri: URI) {
        require(
            authorizationUri.scheme == "https" && !authorizationUri.host.isNullOrBlank() &&
                authorizationUri.rawUserInfo == null && authorizationUri.rawFragment == null,
        ) { "Invalid authorization browser target" }
        val dispatched = try {
            dispatch(authorizationUri.toASCIIString())
            true
        } catch (_: DBusExecutionException) {
            false
        } catch (_: DBusException) {
            false
        } catch (_: IOException) {
            false
        }
        if (!dispatched) throw OAuthBrowserException()
    }
}

internal class OAuthBrowserException : IOException("Unable to open authorization browser")

private fun dispatchOAuthUri(uri: String) {
    val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
    builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
    builder.build().use { connection ->
        val dispatcher = connection.getRemoteObject(
            "com.lomiri.URLDispatcher",
            "/com/lomiri/URLDispatcher",
            OAuthUrlDispatcher::class.java,
        )
        // The second argument restricts the destination package, not the caller identity. Empty uses normal routing.
        // Wait for the method reply before closing the connection, as the platform client does.
        dispatcher.dispatchUrl(uri, "")
    }
}

@DBusInterfaceName("com.lomiri.URLDispatcher")
internal interface OAuthUrlDispatcher : DBusInterface {
    @DBusMemberName("DispatchURL")
    fun dispatchUrl(url: String, destinationPackage: String)
}
