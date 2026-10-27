package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MailOperationViewModelTest {
    @get:Rule val directory = TemporaryFolder()
    private var previousMime: java.io.File? = null
    private val logger = DefaultLogger(object : LogSink {
        override val level = LogLevel.VERBOSE
        override fun log(event: LogEvent) = error("Mail engine must not log fixture content")
    })

    @Before
    fun prepare() {
        previousMime = BinaryTempFileBody.getTempDirectory()
    }

    @After
    fun restore() {
        BinaryTempFileBody.setTempDirectory(previousMime)
    }

    @Test
    fun `mailbox cancel interrupts a real greeting wait and only then clears busy state`() = runTest {
        server().use { server ->
            MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                val manager = manager(database, server)
                val factory = factory(manager, database)
                val testSubject =
                    LiveMailViewModel(manager, PersistentLiveMailRepository(database, factory), backgroundScope)
                val account = manager.findAll().single().id
                testSubject.event(LiveMailEvent.OpenAccount(account))
                testSubject.state.first { !it.busy }
                testSubject.event(LiveMailEvent.Refresh)
                runCurrent()
                server.accept().use {
                    testSubject.event(LiveMailEvent.Cancel)
                    assertThat(testSubject.state.value.busy).isTrue()
                    assertThat(testSubject.state.value.cancelling).isTrue()
                    testSubject.event(LiveMailEvent.Back)
                    assertThat(testSubject.state.value.account).isEqualTo(account)
                    testSubject.state.first { !it.busy }
                    assertThat(testSubject.state.value.canCancel).isFalse()
                    assertThat(testSubject.state.value.notice.orEmpty().contains("Cancelled")).isTrue()
                }
            }
        }
    }

    @Test
    fun `composer cancellation before DATA is failed safely and retains the outbox row`() = runTest {
        server().use { server ->
            MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                val manager = manager(database, server)
                val store = OutgoingMailStore(database)
                val account = manager.findAll().single()
                val draft = store.create(account.id)
                store.save(account.id, draft.id, "recipient@example.invalid", "Fixture", "Body")
                store.queue(account, draft.id)
                val sender = OutgoingMailSender(store, SessionOutgoingTransport(factory(manager, database)))
                val testSubject = ComposerViewModel(manager, store, sender, backgroundScope)
                testSubject.event(ComposerEvent.Account(account.id))
                testSubject.state.first { !it.busy }
                testSubject.event(ComposerEvent.Open(draft.id))
                testSubject.state.first { !it.busy }
                testSubject.event(ComposerEvent.Send)
                runCurrent()
                server.accept().use {
                    testSubject.event(ComposerEvent.Cancel)
                    assertThat(testSubject.state.value.busy).isTrue()
                    assertThat(testSubject.state.value.cancelling).isTrue()
                    testSubject.state.first { !it.busy }
                    assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.FAILED)
                    assertThat(testSubject.state.value.canCancel).isFalse()
                    assertThat(store.get(account.id, draft.id).state).isEqualTo(OutgoingState.FAILED)
                }
            }
        }
    }

    @Test
    fun `cancel during protected credential preparation waits honestly and prevents later TCP connection`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var armed = false
        val underlying = FakeCredentialStore()
        val secrets = object : CredentialStore by underlying {
            override fun read(key: String): ByteArray? {
                if (armed) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return underlying.read(key)
            }
        }
        server().use { server ->
            MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                val manager = manager(database, server, secrets)
                val testSubject = LiveMailViewModel(
                    manager,
                    PersistentLiveMailRepository(database, factory(manager, database)),
                    backgroundScope,
                )
                testSubject.event(LiveMailEvent.OpenAccount(manager.findAll().single().id))
                testSubject.state.first { !it.busy }
                armed = true
                testSubject.event(LiveMailEvent.Refresh)
                runCurrent()
                try {
                    check(entered.await(5, TimeUnit.SECONDS))
                    testSubject.event(LiveMailEvent.Cancel)
                    assertThat(testSubject.state.value.busy).isTrue()
                    assertThat(testSubject.state.value.cancelling).isTrue()
                } finally {
                    release.countDown()
                }
                testSubject.state.first { !it.busy }
                server.soTimeout = 200
                assertThat(runCatching { server.accept() }.exceptionOrNull() is SocketTimeoutException).isTrue()
            }
        }
    }

    @Test
    fun `cancellation request cannot overwrite an already accepted send result`() = runTest {
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        server().use { server ->
            MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
                val manager = manager(database, server)
                val account = manager.findAll().single()
                val store = OutgoingMailStore(database)
                val draft = store.create(account.id)
                store.save(account.id, draft.id, "recipient@example.invalid", "Fixture", "Body")
                store.queue(account, draft.id)
                val sender = OutgoingMailSender(store) { _, _ ->
                    accepted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                val testSubject = ComposerViewModel(manager, store, sender, backgroundScope)
                testSubject.event(ComposerEvent.Account(account.id))
                testSubject.state.first { !it.busy }
                testSubject.event(ComposerEvent.Open(draft.id))
                testSubject.state.first { !it.busy }
                testSubject.event(ComposerEvent.Send)
                runCurrent()
                try {
                    check(accepted.await(5, TimeUnit.SECONDS))
                    testSubject.event(ComposerEvent.Cancel)
                    assertThat(testSubject.state.value.busy).isTrue()
                } finally {
                    release.countDown()
                }
                testSubject.state.first { !it.busy }
                assertThat(testSubject.state.value.selected?.state).isEqualTo(OutgoingState.SENT)
                assertThat(store.get(account.id, draft.id).state).isEqualTo(OutgoingState.SENT)
            }
        }
    }

    private fun server() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }

    private fun factory(manager: UtAccountManager, database: MailDatabase) = ForegroundMailSessionFactory(
        manager,
        database,
        JvmTrustedSocketFactory(),
        logger,
        directory.root.toPath().resolve("mime"),
    )

    private fun manager(
        database: MailDatabase,
        server: ServerSocket,
        secrets: CredentialStore = FakeCredentialStore(),
    ) =
        UtAccountManager(AccountSettingsStorage(database), secrets).apply {
            val settings = ServerSettings(
                "imap",
                server.inetAddress.hostAddress,
                server.localPort,
                ConnectionSecurity.STARTTLS_REQUIRED,
                AuthType.PLAIN,
                "fixture",
                null,
                null,
            )
            createWithPasswords(
                UtMailAccount(
                    AccountIdFactory.create(),
                    "Fixture",
                    "fixture@example.invalid",
                    settings,
                    settings.copy(type = "smtp"),
                    UUID.randomUUID().toString(),
                ),
                AccountPasswords("fixture", "fixture"),
            )
        }
}
