package net.thunderbird.app.ubuntutouch

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Host-only synthetic endpoint. Request bodies and issued fixture credentials are never logged. */
object PhoneOAuthTokenServer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3)
        val output = Path.of(args[1])
        Files.createDirectories(output)
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
            exchange.use { respond(it, calls, output) }
        }
        val stop = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { stop.countDown() })
        try {
            server.start()
            Files.writeString(output.resolve("endpoint.txt"), "https://${args[0]}:${server.address.port}/token")
            println("Controlled OAuth HTTPS server ready")
            stop.await(SERVER_MINUTES, TimeUnit.MINUTES)
        } finally {
            server.stop(0)
            workers.shutdownNow()
            Files.writeString(output.resolve("stopped.txt"), "requests=${calls.get()}\n")
        }
    }

    private fun respond(exchange: HttpExchange, calls: AtomicInteger, output: Path) {
        val raw = exchange.requestBody.readNBytes(MAX_REQUEST + 1)
        val fields = raw.toString(Charsets.UTF_8).split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
        }
        val number = calls.incrementAndGet()
        val grantValid = when (exchange.requestURI.path) {
            "/token" -> number == 1 && fields["grant_type"] == "refresh_token" &&
                fields["refresh_token"] == "R".repeat(PAYLOAD_LENGTH)

            "/exchange" -> number == 2 && fields["grant_type"] == "authorization_code" &&
                fields["code_verifier"]?.let { pkceChallenge(it) == fields["code"] } == true &&
                fields["redirect_uri"]?.startsWith("http://127.0.0.1:") == true

            else -> false
        }
        val valid = exchange.requestMethod == "POST" && raw.size <= MAX_REQUEST &&
            fields["client_id"] == "synthetic-client" && grantValid
        raw.fill(0)
        val response = if (valid) {
            """{"access_token":"controlled-access-$number","refresh_token":"controlled-refresh-$number",
                "token_type":"Bearer","expires_in":3600}"""
        } else {
            """{"error":"invalid_grant"}"""
        }.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(if (valid) OK else BAD_REQUEST, response.size.toLong())
        exchange.responseBody.write(response)
        Files.writeString(output.resolve("requests.txt"), "requests=$number valid=$valid\n")
    }
}

private const val FIXTURE_PASSWORD = "controlled-fixture"
private const val PAYLOAD_LENGTH = 16_384
private const val MAX_REQUEST = 65536
private const val SERVER_MINUTES = 15L
private const val OK = 200
private const val BAD_REQUEST = 400
