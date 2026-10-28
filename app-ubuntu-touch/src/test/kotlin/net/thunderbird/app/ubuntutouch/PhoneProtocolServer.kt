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
        require(args.size in 2..6)
        val startTls = args.size >= 3
        val features = args.drop(3).toSet()
        require(features.all { it in setOf("flags", "reply", "sent", "move", "move-unknown", "copy") })
        if (startTls) require(args[2] == "starttls")
        require("move-unknown" !in features || (startTls && "move" in features))
        require("copy" !in features || (startTls && "move" in features))
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
            serve(server, store, host, output, startTls, features)
        } finally {
            server.stop()
            check(material.toFile().deleteRecursively())
        }
    }

    private fun serve(
        server: GreenMail,
        store: KeyStore,
        host: String,
        output: Path,
        startTls: Boolean,
        features: Set<String>,
    ) {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, PASSWORD.toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val gateways = if (startTls) {
            listOf(
                StartTlsGateway(
                    true,
                    server.imap.port,
                    context,
                    InetAddress.getByName(host),
                    true,
                    dropMoveAcknowledgement = "move-unknown" in features,
                ),
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
            val raw = "From: sender@example.invalid\r\nReply-To: recipient@example.invalid\r\n" +
                "To: fixture@example.invalid\r\nMessage-ID: <native-parent@example.invalid>\r\n" +
                "References: <native-root@example.invalid>\r\n" +
                "Subject: Device controlled inbox\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nControlled incoming body\r\n"
            server.userManager.getUser("fixture").deliver(
                JakartaMessage(JakartaSession.getInstance(Properties()), raw.byteInputStream()),
            )
            if ("sent" in features) prepareFolder(server, "Sent")
            if ("move" in features) prepareFolder(server, "Archive")
            Files.newBufferedWriter(output.resolve("endpoints.properties")).use {
                it.write("host=$host\nimap=${imapPort}\nsmtp=${smtpPort}\n")
            }
            println("READY: controlled TLS IMAP/SMTP on $host")
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            while (!Files.exists(output.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(100)
            verifyServerOutcome(server, features, startTls, gateways)
        } finally {
            gateways.forEach { it.close() }
        }
    }

    private fun verifyServerOutcome(
        server: GreenMail,
        features: Set<String>,
        startTls: Boolean,
        gateways: List<StartTlsGateway>,
    ) {
        val messageId = if ("move" in features) {
            checkMove(
                server,
                sourcePresent = "copy" in features ||
                    ("move-unknown" in features && !gateways.first().droppedMoveWasAtomic.get()),
            )
            ""
        } else {
            checkDelivery(server, features)
        }
        if (startTls) {
            if ("move" in features) {
                check(gateways.first().upgrades.get() >= 2)
            } else {
                check(gateways.all { it.upgrades.get() >= 2 })
            }
        }
        if ("move-unknown" in features) check(gateways.first().droppedMoveAcknowledgements.get() == 1)
        if ("flags" in features) checkFlags(server)
        if ("sent" in features) checkSent(server, messageId, "reply" in features)
    }

    private fun checkDelivery(server: GreenMail, features: Set<String>): String =
        JakartaSession.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.imap.port, "recipient", PASSWORD)
            store.getFolder("INBOX").use { folder ->
                folder.open(jakarta.mail.Folder.READ_ONLY)
                val subject = if ("reply" in features) "Re: Device controlled inbox" else "Device queued outgoing"
                val outgoing = folder.messages.filter { it.subject == subject }
                check(outgoing.size == 1) { "Expected exactly one SMTP delivery in the recipient mailbox" }
                val message = outgoing.single()
                check(message.allRecipients.single().toString() == "recipient@example.invalid")
                check(message.content.toString().trim() == "Phone body λ\r\n.A leading dot")
                if ("reply" in features) checkReply(message)
                println("Independent recipient mailbox: intended recipient, body and single SMTP delivery PASS")
                message.getHeader("Message-ID").single()
            }
        }

    private fun prepareFolder(server: GreenMail, name: String) {
        JakartaSession.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.imap.port, "fixture", PASSWORD)
            check(store.getFolder(name).create(jakarta.mail.Folder.HOLDS_MESSAGES))
        }
    }

    private fun checkMove(server: GreenMail, sourcePresent: Boolean) {
        JakartaSession.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.imap.port, "fixture", PASSWORD)
            store.getFolder("INBOX").use {
                it.open(jakarta.mail.Folder.READ_ONLY)
                check(it.messageCount == if (sourcePresent) 1 else 0)
                if (sourcePresent) check(it.messages.single().content.toString().trim() == "Controlled incoming body")
            }
            store.getFolder("Archive").use {
                it.open(jakarta.mail.Folder.READ_ONLY)
                val message = it.messages.single()
                check(message.subject == "Device controlled inbox")
                check(message.content.toString().trim() == "Controlled incoming body")
            }
        }
        println("Independent IMAP oracle: expected source count and exactly one intact destination copy PASS")
    }

    private fun checkSent(server: GreenMail, messageId: String, reply: Boolean) {
        JakartaSession.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.imap.port, "fixture", PASSWORD)
            store.getFolder("Sent").use { folder ->
                folder.open(jakarta.mail.Folder.READ_ONLY)
                val message = folder.messages.single()
                check(message.isSet(jakarta.mail.Flags.Flag.SEEN))
                check(message.getHeader("Message-ID").single() == messageId)
                check(message.content.toString().trim() == "Phone body λ\r\n.A leading dot")
                if (reply) checkReply(message)
            }
        }
        println("Independent owner Sent mailbox: one matching read copy, body and threading PASS")
    }

    private fun checkReply(message: jakarta.mail.Message) {
        check(message.getHeader("In-Reply-To").single() == "<native-parent@example.invalid>")
        check(
            message.getHeader("References").single() == "<native-root@example.invalid> <native-parent@example.invalid>",
        )
        check(message.getHeader("Message-ID").single() != "<native-parent@example.invalid>")
        println("Independent SMTP oracle: new reply Message-ID, In-Reply-To and References PASS")
    }

    private fun checkFlags(server: GreenMail) {
        JakartaSession.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.imap.port, "fixture", PASSWORD)
            store.getFolder("INBOX").use { folder ->
                folder.open(jakarta.mail.Folder.READ_ONLY)
                check(folder.messages.single().isSet(jakarta.mail.Flags.Flag.SEEN))
                check(folder.messages.single().isSet(jakarta.mail.Flags.Flag.FLAGGED))
            }
        }
        println("Independent IMAP oracle: read and starred flags PASS")
    }

    private const val PASSWORD = "device-fixture-password"
}
