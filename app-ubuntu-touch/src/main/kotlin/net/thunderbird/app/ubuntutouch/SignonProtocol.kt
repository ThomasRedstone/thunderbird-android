package net.thunderbird.app.ubuntutouch

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.MethodNoReply
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

internal const val SIGNON_SERVICE = "com.google.code.AccountsSSO.SingleSignOn"
internal const val SIGNON_PATH = "/com/google/code/AccountsSSO/SingleSignOn"

internal class SignonIdentityReply(
    @field:Position(0) val path: DBusPath,
    @field:Position(1) val data: Map<String, Variant<*>>,
) : Tuple()

@JvmSuppressWildcards
@DBusInterfaceName("com.google.code.AccountsSSO.SingleSignOn.AuthService")
internal interface SignonService : DBusInterface {
    fun registerNewIdentity(applicationContext: String): DBusPath
    fun getIdentity(id: UInt32, applicationContext: String): SignonIdentityReply
    fun getAuthSessionObjectPath(id: UInt32, applicationContext: String, type: String): DBusPath
}

@JvmSuppressWildcards
@DBusInterfaceName("com.google.code.AccountsSSO.SingleSignOn.Identity")
internal interface SignonIdentity : DBusInterface {
    fun store(info: Map<String, Variant<*>>): UInt32
    fun remove()
}

@JvmSuppressWildcards
@DBusInterfaceName("com.google.code.AccountsSSO.SingleSignOn.AuthSession")
internal interface SignonAuthSession : DBusInterface {
    fun process(data: Map<String, Variant<*>>, mechanism: String): Map<String, Variant<*>>

    @MethodNoReply
    fun objectUnref()
}
