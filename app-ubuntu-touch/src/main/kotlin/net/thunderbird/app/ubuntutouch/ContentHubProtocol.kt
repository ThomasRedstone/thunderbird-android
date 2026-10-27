package net.thunderbird.app.ubuntutouch

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

internal const val CONTENT_HUB_SERVICE = "com.lomiri.content.dbus.Service"

@JvmSuppressWildcards
@DBusInterfaceName("com.lomiri.content.dbus.Service")
internal interface ContentHubServiceWire : DBusInterface {
    @DBusMemberName("KnownDestinationsForType")
    fun knownDestinationsForType(type: String): List<Variant<*>>

    @DBusMemberName("CreateExportToPeer")
    fun createExportToPeer(peer: String, app: String, type: String): DBusPath
}

@JvmSuppressWildcards
@DBusInterfaceName("com.lomiri.content.dbus.Transfer")
internal interface ContentHubTransferWire : DBusInterface {
    @DBusMemberName("Start")
    fun start()

    @DBusMemberName("State")
    fun state(): Int

    @DBusMemberName("Charge")
    fun charge(items: List<Variant<*>>)

    @DBusMemberName("Abort")
    fun abort()

    class StateChanged(path: String, val state: Int) : DBusSignal(path, state)
}

/** Platform item wire signature (sayss): stream type, stream bytes, display name, URI. */
internal class ContentHubFileItem(
    @field:Position(0) val streamType: String,
    @field:Position(1) val stream: ByteArray,
    @field:Position(2) val name: String,
    @field:Position(ITEM_URI_POSITION) val uri: String,
) : Struct()

internal fun contentHubExportPrefix(appId: String, label: String): String {
    require(Regex("mail-demo\\.thomasredstone_mail-demo_[A-Za-z0-9.+~:-]{1,64}").matches(appId))
    check(label == "unconfined" || label == "$appId (enforce)") { "Application identity mismatch" }
    val component = appId.map { char ->
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9') {
            char.toString()
        } else {
            "_" + char.code.toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }
    }.joinToString("")
    return "/transfers/$component/export/"
}

internal fun contentHubState(value: Int): ExportBrokerState? = ExportBrokerState.entries.getOrNull(value)

/** Unknown wire shapes fail closed; unusable peer names/IDs are omitted, never silently rewritten into another peer. */
internal fun contentHubDestination(variant: Variant<*>): ExportDestination? {
    require(variant.sig == "(ssaysb)")
    val fields = when (val value = variant.value) {
        is Array<*> -> value
        is Struct -> value.parameters
        else -> error("Invalid Content Hub peer")
    }
    require(fields.size == PEER_FIELDS)
    val id = fields[0] as? String ?: error("Invalid Content Hub peer")
    val name = fields[1] as? String ?: error("Invalid Content Hub peer")
    val icon = fields[2]
    val validIcon = icon is ByteArray || (icon is List<*> && icon.all { it is Byte })
    require(validIcon && fields[PEER_ICON_NAME] is String && fields[PEER_DEFAULT] is Boolean)
    val validId = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,255}").matches(id)
    val unsafeName = name.any {
        Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
    }
    val validName = name.isNotBlank() && name.length <= MAX_PEER_NAME && !unsafeName
    return if (validId && validName) ExportDestination(id, name) else null
}

private const val HEX_RADIX = 16
private const val PEER_FIELDS = 5
private const val MAX_PEER_NAME = 512

private const val HEX_BYTE_WIDTH = 2
private const val PEER_ICON_NAME = 3
private const val PEER_DEFAULT = 4

private const val ITEM_URI_POSITION = 3
