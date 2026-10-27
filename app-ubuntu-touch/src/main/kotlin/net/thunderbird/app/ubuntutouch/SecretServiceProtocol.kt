@file:Suppress("MagicNumber") // Fixed D-Bus wire field positions.

package net.thunderbird.app.ubuntutouch

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

internal class SecretSessionReply(
    @field:Position(0) val output: Variant<*>,
    @field:Position(1) val session: DBusPath,
) : Tuple()

internal class SecretSearchReply(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val locked: List<DBusPath>,
) : Tuple()

internal class SecretCreateReply(
    @field:Position(0) val item: DBusPath,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

internal class SecretValue(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Service")
internal interface SecretService : DBusInterface {
    @DBusMemberName("OpenSession")
    fun openSession(algorithm: String, input: Variant<*>): SecretSessionReply

    @DBusMemberName("SearchItems")
    fun searchItems(attributes: Map<String, String>): SecretSearchReply

    @DBusMemberName("ReadAlias")
    fun readAlias(name: String): DBusPath
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Collection")
internal interface SecretCollection : DBusInterface {
    @DBusMemberName("CreateItem")
    fun createItem(properties: Map<String, Variant<*>>, secret: SecretValue, replace: Boolean): SecretCreateReply
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
internal interface SecretItem : DBusInterface {
    @DBusMemberName("GetSecret")
    fun getSecret(session: DBusPath): SecretValue

    @DBusMemberName("Delete")
    fun delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Session")
internal interface SecretSession : DBusInterface {
    @DBusMemberName("Close")
    fun close()
}
