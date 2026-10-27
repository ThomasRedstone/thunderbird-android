package net.thunderbird.app.ubuntutouch

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

internal const val MALIIT_SERVER_PATH = "/com/meego/inputmethod/uiserver1"
internal const val MALIIT_CONTEXT_PATH = "/com/meego/inputmethod/inputcontext"

// Always send both modes explicitly so a normal field cannot inherit password hints, or vice versa.
internal fun maliitWidgetState(hiddenText: Boolean): Map<String, Variant<*>> = mapOf(
    "focusState" to Variant(true),
    "hiddenText" to Variant(hiddenText),
    "predictionEnabled" to Variant(!hiddenText),
    "correctionEnabled" to Variant(!hiddenText),
    "autocapitalizationEnabled" to Variant(!hiddenText),
)

// Wire names and signatures match ut-platform/src/ime.rs. Maliit's callbacks
// are commitString/updatePreedit, not the adaptor's C++ send-prefixed names.
@JvmSuppressWildcards
@DBusInterfaceName("com.meego.inputmethod.uiserver1")
internal interface MaliitServer : DBusInterface {
    fun activateContext()
    fun updateWidgetInformation(state: Map<String, Variant<*>>, focusChanged: Boolean)
    fun showInputMethod()
    fun hideInputMethod()
    fun reset()
}

internal class MaliitPreeditFormat(
    @field:Position(0) val start: Int,
    @field:Position(1) val length: Int,
    @field:Position(2) val format: Int,
) : Struct()

@Suppress("LongParameterList") // Fixed Maliit wire signatures.
@JvmSuppressWildcards
@DBusInterfaceName("com.meego.inputmethod.inputcontext1")
internal interface MaliitInputContext : DBusInterface {
    fun commitString(text: String, replaceStart: Int, replaceLength: Int, cursorPosition: Int)
    fun updatePreedit(
        text: String,
        formats: List<MaliitPreeditFormat>,
        replaceStart: Int,
        replaceLength: Int,
        cursorPosition: Int,
    )
    fun keyEvent(type: Int, key: Int, modifiers: Int, text: String, autoRepeat: Boolean, count: Int, requestType: Byte)
    fun updateInputMethodArea(x: Int, y: Int, width: Int, height: Int)
    fun notifyImInitiatedHiding()
}
