package net.thunderbird.app.ubuntutouch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.core.common.oauth.OAuthConfiguration

/** One attempt, one waiter. The setup owner must close this on exit, including before await starts. */
internal class OAuthLoopbackReceiver private constructor(
    private val server: ServerSocket,
    private val attempt: OAuthAuthorizationAttempt,
    private val deadline: Long,
    private val requestTimeoutMillis: Int,
) : AutoCloseable {
    val authorizationUri: URI get() = attempt.authorizationUri
    private val waiting = AtomicBoolean()
    private val lock = Any()
    private var closed = false
    private var peer: Socket? = null

    suspend fun await(): OAuthCallback = coroutineScope {
        check(waiting.compareAndSet(false, true)) { "Callback receiver already awaited" }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            launch(Dispatchers.IO) {
                try {
                    continuation.resume(receive())
                } catch (_: IOException) {
                    continuation.resumeWithException(OAuthLoopbackException())
                } finally {
                    close()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                attempt.cancel()
                try {
                    peer?.close()
                } finally {
                    server.close()
                }
            }
        }
    }

    override fun toString(): String = "OAuthLoopbackReceiver(redacted)"

    private fun receive(): OAuthCallback {
        repeat(MAX_CONNECTIONS) {
            server.soTimeout = remainingMillis(deadline)
            val socket = server.accept()
            socket.use {
                synchronized(lock) {
                    if (closed) throw IOException("Callback receiver closed")
                    peer = socket
                }
                val result = try {
                    val requestDeadline = minOf(
                        deadline,
                        System.nanoTime() + requestTimeoutMillis.toLong() * NANOS_PER_MILLI,
                    )
                    val target = readTarget(socket, requestDeadline, server.localPort)
                    target?.let { attempt.accept("http://127.0.0.1:${server.localPort}$it") }
                } catch (_: IOException) {
                    null // Malformed/slow/disconnected local clients must not consume the attempt.
                }
                respond(socket, result != null)
                synchronized(lock) { peer = null }
                if (result != null) return result
            }
        }
        throw IOException("Callback connection limit reached")
    }

    companion object {
        /** The caller supplies configuration for the allocated redirect. This does not register it with a provider. */
        fun open(
            configuration: (URI) -> OAuthConfiguration,
            totalTimeoutMillis: Int = DEFAULT_TOTAL_TIMEOUT,
            requestTimeoutMillis: Int = DEFAULT_REQUEST_TIMEOUT,
        ): OAuthLoopbackReceiver {
            require(totalTimeoutMillis in 1..DEFAULT_TOTAL_TIMEOUT)
            require(requestTimeoutMillis in 1..totalTimeoutMillis)
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            var transferred = false
            try {
                val redirect = URI("http://127.0.0.1:${server.localPort}/oauth2/callback")
                val supplied = configuration(redirect)
                require(supplied.redirectUri == redirect.toASCIIString()) { "Callback configuration mismatch" }
                val attempt = OAuthAuthorizationAttempt.create(supplied)
                return OAuthLoopbackReceiver(
                    server,
                    attempt,
                    System.nanoTime() + totalTimeoutMillis.toLong() * NANOS_PER_MILLI,
                    requestTimeoutMillis,
                )
                    .also { transferred = true }
            } finally {
                if (!transferred) server.close()
            }
        }
    }
}

internal class OAuthLoopbackException : IOException("OAuth callback unavailable or timed out")

private fun remainingMillis(deadline: Long): Int {
    val remaining = deadline - System.nanoTime()
    if (remaining <= 0) throw SocketTimeoutException("Callback deadline reached")
    return ((remaining / NANOS_PER_MILLI) + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

@Suppress("ReturnCount") // Reject invalid HTTP before handing any target to OAuth state validation.
private fun readTarget(socket: Socket, deadline: Long, port: Int): String? {
    val headersText = readHeaderBlock(socket, deadline) ?: return null
    val lines = headersText.removeSuffix("\r\n\r\n").split("\r\n")
    val request = lines.first().split(' ')
    if (request.size != REQUEST_FIELDS || request[0] != "GET" || request[2] != "HTTP/1.1") return null
    val target = request[1]
    if (!target.startsWith("/") || target.startsWith("//")) return null
    val headers = parseHeaders(lines.drop(1)) ?: return null
    if (headers["host"] != "127.0.0.1:$port" || "transfer-encoding" in headers) return null
    if (headers["content-length"]?.let { it != "0" } == true) return null
    return target
}

private fun respond(socket: Socket, accepted: Boolean) {
    val status = if (accepted) "200 OK" else "400 Bad Request"
    val body = if (accepted) "Return to Mail demo to continue." else "Callback rejected."
    val response = "HTTP/1.1 $status\r\nConnection: close\r\nCache-Control: no-store\r\n" +
        "Content-Type: text/plain; charset=utf-8\r\nContent-Security-Policy: default-src 'none'\r\n" +
        "Referrer-Policy: no-referrer\r\nContent-Length: ${body.length}\r\n\r\n$body"
    try {
        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
    } catch (_: IOException) {
        // Browser disconnect does not discard an already validated code. No request data is reflected or logged.
    }
}

private const val DEFAULT_TOTAL_TIMEOUT = 300_000
private const val DEFAULT_REQUEST_TIMEOUT = 2000
private const val MAX_CONNECTIONS = 32
private const val MAX_HEADERS = 16_384
private const val NANOS_PER_MILLI = 1_000_000L
private const val LAST_ASCII = 127
private const val BITS_PER_BYTE = 8
private const val HEADER_END = 0x0d0a0d0a
private const val REQUEST_FIELDS = 3
private const val FIRST_PRINTABLE_ASCII = 32
private val PRINTABLE_ASCII = FIRST_PRINTABLE_ASCII until LAST_ASCII
private val HEADER_NAME = Regex("[a-z0-9!#$%&'*+.^_`|~-]+")

private fun readHeaderBlock(socket: Socket, deadline: Long): String? {
    val bytes = ByteArrayOutputStream()
    val input = socket.getInputStream()
    var tail = 0
    while (bytes.size() < MAX_HEADERS) {
        socket.soTimeout = remainingMillis(deadline)
        val value = input.read()
        if (value < 0 || value > LAST_ASCII) return null
        bytes.write(value)
        tail = (tail shl BITS_PER_BYTE) or value
        if (tail == HEADER_END) break
    }
    return if (tail == HEADER_END) bytes.toString(Charsets.US_ASCII) else null
}

@Suppress("ReturnCount") // Reject duplicate and malformed header fields before interpreting Host or body framing.
private fun parseHeaders(lines: List<String>): Map<String, String>? {
    val headers = mutableMapOf<String, String>()
    for (line in lines) {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val name = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
        val value = line.substring(colon + 1).trim(' ')
        if (!HEADER_NAME.matches(name) || value.any { it.code !in PRINTABLE_ASCII }) return null
        if (headers.put(name, value) != null) return null
    }
    return headers
}
