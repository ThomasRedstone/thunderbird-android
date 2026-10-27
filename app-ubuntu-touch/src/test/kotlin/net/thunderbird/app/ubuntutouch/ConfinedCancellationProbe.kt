package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.ssl.PlainSocketFactory
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory

/** Opt-in headless model check against a controlled TCP peer that accepts but never sends its greeting. */
object ConfinedCancellationProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 3)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args[0]} (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "cancel-runtime-")
        try {
            MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                check(database.query("PRAGMA user_version") { getLong(0) }.single() == 13L)
                checkCancellation(database, directory, args[1], args[2].toInt())
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                    val manager = manager(database)
                    manager.findAll().forEach { manager.delete(it.id) }
                    check(manager.cleanupUnusedCredentials()) { "Fixture cleanup pending; retain its private index" }
                }
                check(directory.toFile().deleteRecursively())
            }
        }
        println("Enforcing profile, schema 9, protected account and model-driven pre-STARTTLS cancellation: PASS")
    }

    private suspend fun checkCancellation(database: MailDatabase, directory: Path, host: String, port: Int) {
        var logs = 0
        val logger = DefaultLogger(object : LogSink {
            override val level = LogLevel.VERBOSE
            override fun log(event: LogEvent) {
                logs++
            }
        })
        val connected = CountDownLatch(1)
        val sockets = ObservedPlainSockets(connected)
        val manager = withContext(Dispatchers.IO) { manager(database) }
        val settings = ServerSettings(
            "imap",
            host,
            port,
            ConnectionSecurity.STARTTLS_REQUIRED,
            AuthType.PLAIN,
            "fixture",
            null,
            null,
        )
        val account = UtMailAccount(
            AccountIdFactory.create(),
            "Controlled cancellation",
            "fixture@example.invalid",
            settings,
            settings.copy(type = "smtp"),
            UUID.randomUUID().toString(),
        )
        withContext(Dispatchers.IO) { manager.createWithPasswords(account, AccountPasswords("fixture", "fixture")) }
        val sessions = ForegroundMailSessionFactory(manager, database, sockets, logger, directory.resolve("mime"))
        val model = LiveMailViewModel(manager, PersistentLiveMailRepository(database, sessions))
        try {
            withContext(Dispatchers.Main) { model.event(LiveMailEvent.OpenAccount(account.id)) }
            withTimeout(10000) { model.state.first { !it.busy } }
            withContext(Dispatchers.Main) { model.event(LiveMailEvent.Refresh) }
            withContext(Dispatchers.IO) { check(connected.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) {
                check(model.state.value.busy && model.state.value.canCancel)
                model.event(LiveMailEvent.Cancel)
                check(model.state.value.busy && model.state.value.cancelling)
            }
            withTimeout(5000) { model.state.first { !it.busy } }
            check(!model.state.value.canCancel && !model.state.value.cancelling)
            check(model.state.value.notice?.contains("Cancelled") == true)
            check(logs == 0)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { model.close() }
        }
    }

    private fun manager(database: MailDatabase) = UtAccountManager(
        AccountSettingsStorage(database),
        SignonCredentialStore(SignonIdentityIndex(database)),
    )
}

/** Observe TCP establishment only; preserve the real JVM TLS adapter and production model/socket-owner code. */
private class ObservedPlainSockets(private val connected: CountDownLatch) : TrustedSocketFactory, PlainSocketFactory {
    private val delegate = JvmTrustedSocketFactory()
    override fun createSocket(socket: Socket?, host: String, port: Int, clientCertificateAlias: String?): Socket =
        delegate.createSocket(socket, host, port, clientCertificateAlias)
    override fun createPlainSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(endpoint, timeout)
            connected.countDown()
        }
    }
}
