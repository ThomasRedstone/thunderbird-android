package net.thunderbird.app.ubuntutouch

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Host-only bounded fixture. No request headers, profile payloads or bearer values are logged. */
object PhoneOAuthIdentityServer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3)
        val output = Files.createDirectory(Path.of(args[1]))
        val keys = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(Path.of(args[2])).use { load(it, FIXTURE_PASSWORD.toCharArray()) }
        }
        Files.write(output.resolve("certificate.der"), keys.getCertificate("server").encoded)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, FIXTURE_PASSWORD.toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress(args[0], 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        val workers = Executors.newSingleThreadExecutor()
        server.executor = workers
        val calls = AtomicInteger()
        server.createContext("/") { exchange ->
            exchange.use {
                val valid = it.requestMethod == "GET" && it.requestURI.path == "/profile" &&
                    it.requestURI.rawQuery == null &&
                    it.requestHeaders.getFirst("Authorization") == "Bearer identity-access"
                val bytes = if (valid) {
                    """{"sub":"native-profile-subject","email":"fixture@example.invalid","email_verified":true}"""
                } else {
                    """{"error":"invalid_request"}"""
                }
                val body = bytes.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(if (valid) OK else BAD_REQUEST, body.size.toLong())
                it.responseBody.write(body)
                Files.writeString(output.resolve("requests.txt"), "requests=${calls.incrementAndGet()} valid=$valid\n")
            }
        }
        val stop = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { stop.countDown() })
        try {
            server.start()
            Files.writeString(output.resolve("endpoint.txt"), "https://${args[0]}:${server.address.port}/profile")
            println("Controlled OAuth identity HTTPS server ready")
            stop.await(SERVER_MINUTES, TimeUnit.MINUTES)
        } finally {
            server.stop(0)
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

private const val FIXTURE_PASSWORD = "controlled-fixture"
private const val SERVER_MINUTES = 15L
private const val OK = 200
private const val BAD_REQUEST = 400
