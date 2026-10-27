package net.thunderbird.app.ubuntutouch

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import jakarta.mail.Session as JakartaSession
import jakarta.mail.internet.MimeMessage as JakartaMessage

/** Explicit host fixture. Binds only the supplied local test interface and never forwards mail. */
object PhoneProtocolServer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3)
        val startTls = args.size == 3
        if (startTls) require(args[2] == "starttls")
        val host = args[0]
        val output = Path.of(args[1]).toAbsolutePath()
        Files.createDirectories(output)
        val material = Files.createTempDirectory("phone-mail-server-")
        val keys = material.resolve("server.p12")
        val generator = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "server",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=Controlled phone fixture",
            "-ext", "SAN=ip:$host", "-storetype", "PKCS12", "-keystore", keys.toString(),
            "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(generator.waitFor(30, TimeUnit.SECONDS) && generator.exitValue() == 0)
        val store = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(keys).use { load(it, PASSWORD.toCharArray()) }
        }
        Files.write(output.resolve("fixture.cer"), store.getCertificate("server").encoded)
        System.setProperty("greenmail.tls.keystore.file", keys.toString())
        System.setProperty("greenmail.tls.keystore.password", PASSWORD)
        val server = GreenMail(
            arrayOf(
                ServerSetup(0, host, ServerSetup.PROTOCOL_IMAPS),
                ServerSetup(0, host, ServerSetup.PROTOCOL_SMTPS),
                ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
                ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP),
            ),
        )
        try {
            server.start()
            serve(server, store, host, output, startTls)
        } finally {
            server.stop()
            check(material.toFile().deleteRecursively())
        }
    }

    private fun serve(server: GreenMail, store: KeyStore, host: String, output: Path, startTls: Boolean) {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, PASSWORD.toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val gateways = if (startTls) {
            listOf(
                StartTlsGateway(true, server.imap.port, context, InetAddress.getByName(host), true),
                StartTlsGateway(false, server.smtp.port, context, InetAddress.getByName(host), true),
            )
        } else {
            emptyList()
        }
        try {
            val imapPort = gateways.getOrNull(0)?.port ?: server.imaps.port
            val smtpPort = gateways.getOrNull(1)?.port ?: server.smtps.port
            server.setUser("fixture@example.invalid", "fixture", PASSWORD)
            server.setUser("recipient@example.invalid", "recipient", PASSWORD)
            val raw = "From: sender@example.invalid\r\nTo: fixture@example.invalid\r\n" +
                "Subject: Device controlled inbox\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nControlled incoming body\r\n"
            server.userManager.getUser("fixture").deliver(
                JakartaMessage(JakartaSession.getInstance(Properties()), raw.byteInputStream()),
            )
            Files.newBufferedWriter(output.resolve("endpoints.properties")).use {
                it.write("host=$host\nimap=${imapPort}\nsmtp=${smtpPort}\n")
            }
            println("READY: controlled TLS IMAP/SMTP on $host")
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            while (!Files.exists(output.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(100)
            val outgoing = server.receivedMessages.filter { it.subject == "Device queued outgoing" }
            check(outgoing.size == 1) { "Expected exactly one SMTP-received message" }
            check(outgoing.single().allRecipients.single().toString() == "recipient@example.invalid")
            check(outgoing.single().content.toString().trim() == "Phone body λ\r\n.A leading dot")
            check(outgoing.single().messageID.isNotBlank())
            println(
                "Independent server observation: intended recipient, Unicode/dot-stuffed body and single delivery PASS",
            )
            if (startTls) check(gateways.all { it.upgrades.get() >= 2 })
        } finally {
            gateways.forEach { it.close() }
        }
    }

    private const val PASSWORD = "device-fixture-password"
}
