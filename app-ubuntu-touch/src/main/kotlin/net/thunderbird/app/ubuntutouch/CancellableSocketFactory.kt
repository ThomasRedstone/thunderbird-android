package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.ssl.PlainSocketFactory
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Socket

/** A fresh owner per session. Close rejects future connections and interrupts existing blocking socket operations. */
internal class CancellableSocketFactory(private val delegate: TrustedSocketFactory) :
    TrustedSocketFactory, PlainSocketFactory, AutoCloseable {
    private val monitor = Any()
    private var closed = false
    private val sockets = linkedSetOf<Socket>()

    override fun createPlainSocket(): Socket = register(Socket())

    override fun createSocket(socket: Socket?, host: String, port: Int, clientCertificateAlias: String?): Socket =
        register(delegate.createSocket(socket, host, port, clientCertificateAlias))

    private fun register(socket: Socket): Socket {
        synchronized(monitor) {
            if (!closed) {
                sockets += socket
                return socket
            }
        }
        closeSocket(socket)
        throw InterruptedIOException("Mail session closed")
    }

    override fun close() {
        val pending = synchronized(monitor) {
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        // Close underlying plain sockets before their TLS wrappers. Never hold the owner lock across socket IO.
        pending.forEach(::closeSocket)
    }

    private fun closeSocket(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Continue closing the remaining sockets; no server/credential data enters diagnostics.
        }
    }
}
