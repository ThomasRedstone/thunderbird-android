package net.thunderbird.app.ubuntutouch

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Test-only STARTTLS front end. Authentication and mail are forwarded only after the TLS handshake. */
internal class StartTlsGateway(
    private val incoming: Boolean,
    private val backendPort: Int,
    private val context: SSLContext,
    address: InetAddress = InetAddress.getLoopbackAddress(),
    private val allowRejectedHandshake: Boolean = false,
) : AutoCloseable {
    private val listener = ServerSocket(0, 10, address)
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val failures = ConcurrentLinkedQueue<Exception>()
    val upgrades = AtomicInteger()
    val port: Int get() = listener.localPort

    init {
        workers.submit {
            while (!listener.isClosed) {
                val peer = try {
                    listener.accept()
                } catch (_: java.net.SocketException) {
                    break
                }
                sockets.add(peer)
                workers.submit {
                    try {
                        peer.use { upgrade(it) }
                    } catch (failure: Exception) {
                        failures.add(failure)
                    }
                }
            }
        }
    }

    private fun upgrade(peer: Socket) {
        peer.soTimeout = 10000
        if (incoming) imapUpgrade(peer) else smtpUpgrade(peer)
        val encrypted = context.socketFactory.createSocket(peer, "localhost", peer.port, false) as SSLSocket
        encrypted.useClientMode = false
        encrypted.soTimeout = 10000
        try {
            encrypted.startHandshake()
        } catch (failure: javax.net.ssl.SSLException) {
            if (!allowRejectedHandshake) throw failure
            return
        }
        upgrades.incrementAndGet()
        Socket(InetAddress.getLoopbackAddress(), backendPort).use { backend ->
            sockets.add(backend)
            backend.soTimeout = 10000
            // Consume the backend greeting: the front end already supplied its own before STARTTLS.
            readLine(backend)
            workers.submit {
                try {
                    encrypted.inputStream.copyTo(backend.outputStream)
                } catch (_: java.io.IOException) {
                    // Either endpoint can close an established session normally.
                } finally {
                    backend.close()
                }
            }
            try {
                backend.inputStream.copyTo(encrypted.outputStream)
            } catch (_: java.io.IOException) {
                // The production engine closes owned sockets when a session ends.
            } finally {
                sockets.remove(backend)
                sockets.remove(peer)
            }
        }
    }

    private fun imapUpgrade(peer: Socket) {
        reply(peer, "* OK [CAPABILITY IMAP4rev1 STARTTLS LOGINDISABLED] Controlled fixture")
        val command = readLine(peer).split(' ')
        check(command.size == 2 && command[1] == "STARTTLS") { "IMAP attempted a command before TLS" }
        reply(peer, "${command[0]} OK Begin TLS")
    }

    private fun smtpUpgrade(peer: Socket) {
        reply(peer, "220 Controlled fixture")
        check(readLine(peer).startsWith("EHLO ")) { "Expected unauthenticated EHLO" }
        reply(peer, "250-Controlled fixture\r\n250 STARTTLS")
        check(readLine(peer) == "STARTTLS") { "SMTP attempted a command before TLS" }
        reply(peer, "220 Begin TLS")
    }

    private fun reply(peer: Socket, text: String) {
        peer.outputStream.write("$text\r\n".toByteArray(Charsets.US_ASCII))
        peer.outputStream.flush()
    }

    private fun readLine(peer: Socket): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = peer.inputStream.read()
            check(next >= 0 && bytes.size < 8192) { "Truncated or oversized fixture command" }
            if (next == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(next.toByte())
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS))
        check(failures.isEmpty()) { "STARTTLS fixture peer failed: ${failures.first().javaClass.simpleName}" }
    }
}

internal class StartTlsMaterial(directory: Path) {
    val trust: KeyStore
    val context: SSLContext

    init {
        val path = directory.resolve("starttls.p12")
        val password = "controlled-fixture"
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "server", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
            "-storetype", "PKCS12", "-keystore", path.toString(),
            "-storepass", password, "-keypass", password, "-noprompt",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
        val keys = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(path).use { load(it, password.toCharArray()) }
        }
        trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("fixture", keys.getCertificate("server"))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, password.toCharArray())
        }
        context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }
}
