package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.thunderbird.components.core.logging.Logger
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.impl.DirectConnection
import org.freedesktop.dbus.connections.impl.DirectConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

internal class MaliitKeyboard(private val logger: Logger) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val edits = Channel<MaliitTextEdit>(Channel.BUFFERED)
    private val mutableHeight = MutableStateFlow(0)
    private val mutableUnavailable = MutableStateFlow(false)
    val height = mutableHeight.asStateFlow()
    val unavailable = mutableUnavailable.asStateFlow()
    val events = edits.receiveAsFlow()

    @Volatile
    var focused = false
        private set

    private var textFocused = false
    private var windowFocused = true
    private var connection: DirectConnection? = null
    private var server: MaliitServer? = null
    private val context = InputContext()

    fun focus(focused: Boolean) {
        textFocused = focused
        updateFocus()
    }

    fun windowFocusChanged(focused: Boolean) {
        windowFocused = focused
        updateFocus()
    }

    private fun updateFocus() {
        val focused = textFocused && windowFocused
        if (this.focused == focused) return
        this.focused = focused
        scope.launch {
            runCatching {
                if (focused) {
                    val keyboard = connect()
                    keyboard.activateContext()
                    keyboard.updateWidgetInformation(mapOf("focusState" to Variant(true)), true)
                    keyboard.showInputMethod()
                    mutableUnavailable.value = false
                } else {
                    // Reset composing state before hiding so it cannot leak into the next field.
                    server?.reset()
                    server?.hideInputMethod()
                    mutableHeight.value = 0
                }
            }.onFailure {
                logger.warn(tag = "MaliitKeyboard", throwable = it) { "Unable to update keyboard focus" }
                mutableUnavailable.value = focused
                runCatching { connection?.disconnect() }
                connection = null
                server = null
            }
        }
    }

    private fun connect(): MaliitServer {
        server?.let { return it }
        val uid = UnixSystem().uid
        val sessionBuilder = DBusConnectionBuilder.forSessionBus().withShared(false)
        sessionBuilder.transportConfig().configureSasl().withSaslUid(uid)
        val address = sessionBuilder.build().use { session ->
            session.getRemoteObject("org.maliit.server", "/org/maliit/server/address", Properties::class.java)
                .Get<String>("org.maliit.Server.Address", "address")
        }
        val peerBuilder = DirectConnectionBuilder.forAddress(address).withExportWeakReferences(false)
        peerBuilder.transportConfig().configureSasl().withSaslUid(uid)
        peerBuilder.receivingThreadConfig().withMethodCallThreadCount(1).withSignalThreadCount(1)
            .withMethodReturnThreadCount(1).withErrorHandlerThreadCount(1)
        val peer = peerBuilder.build()
        connection = peer
        peer.exportObject(MALIIT_CONTEXT_PATH, context)
        logger.info(tag = "MaliitKeyboard") { "Connected to keyboard peer" }
        return peer.getRemoteObject(MALIIT_SERVER_PATH, MaliitServer::class.java).also { server = it }
    }

    private fun emit(edit: MaliitTextEdit) {
        if (focused && edits.trySend(edit).isFailure) mutableUnavailable.value = true
    }

    private inner class InputContext : MaliitInputContext {
        override fun getObjectPath() = MALIIT_CONTEXT_PATH
        override fun commitString(text: String, replaceStart: Int, replaceLength: Int, cursorPosition: Int) {
            emit(MaliitTextEdit.Commit(text))
        }
        override fun updatePreedit(
            text: String,
            formats: List<MaliitPreeditFormat>,
            replaceStart: Int,
            replaceLength: Int,
            cursorPosition: Int,
        ) {
            emit(MaliitTextEdit.Preedit(text))
        }
        override fun keyEvent(
            type: Int,
            key: Int,
            modifiers: Int,
            text: String,
            autoRepeat: Boolean,
            count: Int,
            requestType: Byte,
        ) {
            if (type != QT_KEY_PRESS) return
            when (key) {
                QT_BACKSPACE -> emit(MaliitTextEdit.Backspace)
                QT_RETURN, QT_ENTER -> emit(MaliitTextEdit.Enter)
            }
        }
        override fun updateInputMethodArea(x: Int, y: Int, width: Int, height: Int) {
            mutableHeight.value = height.coerceAtLeast(0)
        }
        override fun notifyImInitiatedHiding() {
            mutableHeight.value = 0
        }
    }
}

private const val QT_KEY_PRESS = 6
private const val QT_BACKSPACE = 0x01000003
private const val QT_RETURN = 0x01000004
private const val QT_ENTER = 0x01000005
