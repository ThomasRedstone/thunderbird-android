package net.thunderbird.app.ubuntutouch

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val dropAppendAcknowledgement: Boolean = false,
    private val dropFetchBody: Boolean = false,
    private val stallFetchBody: Boolean = false,
) : AutoCloseable {
    private val listener = ServerSocket(0, 10, address)
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val failures = ConcurrentLinkedQueue<Exception>()
    val upgrades = AtomicInteger()
    val droppedAppendAcknowledgements = AtomicInteger()
    val truncatedFetchBodies = AtomicInteger()
    val bodyTransferStarted = CountDownLatch(1)
    val clientConnectionClosed = CountDownLatch(1)
    private val appendResponseDropped = AtomicBoolean()
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
                    clientConnectionClosed.countDown()
                    backend.close()
                }
            }
            try {
                if (dropAppendAcknowledgement || dropFetchBody || stallFetchBody) {
                    relayWithFault(backend, encrypted)
                } else {
                    backend.inputStream.copyTo(encrypted.outputStream)
                }
            } catch (_: java.io.IOException) {
                // The production engine closes owned sockets when a session ends.
            } finally {
                sockets.remove(backend)
                sockets.remove(peer)
            }
        }
    }

    /** Test-only response faults. Literal truncation closes immediately; normal body streaming uses the byte relay. */
    private fun relayWithFault(backend: Socket, encrypted: Socket) {
        while (true) {
            val response = try {
                readLine(backend)
            } catch (_: java.io.EOFException) {
                return
            }
            val appendAccepted = Regex("^[^ ]+ OK .*APPEND.*", RegexOption.IGNORE_CASE).matches(response)
            if (dropAppendAcknowledgement && appendAccepted && appendResponseDropped.compareAndSet(false, true)) {
                droppedAppendAcknowledgements.incrementAndGet()
                encrypted.close()
                return
            }
            reply(encrypted, response)
            if (truncateFetch(backend, encrypted, response)) break
        }
    }

    private fun truncateFetch(backend: Socket, encrypted: Socket, response: String): Boolean {
        if ((dropFetchBody || stallFetchBody) && Regex("""^\* .* FETCH .*BODY\[\].*\{[0-9]+\}$""").matches(response)) {
            val prefix = backend.inputStream.readNBytes(16)
            check(prefix.size == 16)
            encrypted.outputStream.write(prefix)
            encrypted.outputStream.flush()
            bodyTransferStarted.countDown()
            if (stallFetchBody) {
                check(clientConnectionClosed.await(5, TimeUnit.SECONDS)) {
                    "Client did not close stalled body transfer"
                }
            } else {
                truncatedFetchBodies.incrementAndGet()
                encrypted.close()
            }
            return true
        }
        return false
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
            if (next < 0) throw java.io.EOFException()
            check(bytes.size < 8192) { "Truncated or oversized fixture command" }
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
    val path: Path = directory.resolve("starttls.p12")
    val password = "controlled-fixture"

    init {
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
