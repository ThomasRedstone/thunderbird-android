package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.transport.smtp.SmtpTransport
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import java.util.concurrent.atomic.AtomicInteger
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OAuthEngineIntegrationTest {
    @get:Rule val directory = TemporaryFolder()
    private val account = OAuthGrant(AccountIdFactory.create())
    private val configuration = OAuthConfiguration(
        "synthetic",
        listOf("mail"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )

    @Test
    fun `real IMAP and SMTP engines reject stale bearer then refresh and authenticate over STARTTLS`() {
        fixture { server, sockets, imapPort, smtpPort ->
            val user = server.setUser(PRIMARY, PRIMARY, "unaccepted-initial-token")
            val store = OAuthTokenStore(FakeCredentialStore())
            val calls = AtomicInteger()
            val manager = OAuthTokenCoordinator(
                store,
                OAuthTokenClient(
                    OAuthTokenHttp { _, _ ->
                        val number = calls.incrementAndGet()
                        user.setPassword("access-$number")
                        OAuthHttpReply(
                            200,
                            "application/json",
                            """
                    {"access_token":"access-$number","refresh_token":"refresh-$number",
                    "token_type":"Bearer","expires_in":3600}
                            """.trimIndent().toByteArray(),
                        )
                    },
                ) { 0 },
            ) { 0 }
            store.write(account, configuration, OAuthTokens("rejected-imap", "refresh", 500000))
            CoordinatedOAuth2TokenProvider(account, configuration, manager).use { provider ->
                val imap = ImapStore.create(settings("imap", imapPort), Config, sockets, provider)
                try {
                    imap.checkSettings()
                    assertThat(calls.get()).isEqualTo(1)
                } finally {
                    imap.closeAllConnections()
                }
            }
            store.write(account, configuration, OAuthTokens("rejected-smtp", "refresh-1", 500000))
            CoordinatedOAuth2TokenProvider(account, configuration, manager).use { provider ->
                SmtpTransport(settings("smtp", smtpPort), sockets, provider).checkSettings()
            }
            assertThat(calls.get()).isEqualTo(2)
            assertThat(store.read(account, configuration)!!.accessToken).isEqualTo("access-2")
        }
    }

    @Test
    fun `SMTP engine can use supplied verified primary identity when typed alias fails with fresh token`() {
        fixture(expectImap = false) { server, sockets, _, smtpPort ->
            server.setUser(PRIMARY, PRIMARY, "fresh-access")
            val store = OAuthTokenStore(FakeCredentialStore())
            store.write(account, configuration, OAuthTokens("fresh-access", "refresh", 500000))
            val client = OAuthTokenClient(OAuthTokenHttp { _, _ -> error("Unexpected refresh") })
            val manager = OAuthTokenCoordinator(store, client) { 0 }
            CoordinatedOAuth2TokenProvider(account, configuration, manager, setOf(PRIMARY)).use { provider ->
                val outgoing = settings("smtp", smtpPort).copy(username = "alias@example.invalid")
                SmtpTransport(outgoing, sockets, provider).checkSettings()
                assertThat(store.read(account, configuration)!!.accessToken).isEqualTo("fresh-access")
            }
        }
    }

    @Test
    fun `SMTP retries verified primary identity after stale token refresh when configured alias is rejected`() {
        fixture(expectImap = false) { server, sockets, _, smtpPort ->
            server.setUser(PRIMARY, PRIMARY, "fresh-access")
            val store = OAuthTokenStore(FakeCredentialStore())
            store.write(account, configuration, OAuthTokens("rejected-access", "refresh", 500000))
            val calls = AtomicInteger()
            val client = OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    calls.incrementAndGet()
                    OAuthHttpReply(
                        200,
                        "application/json",
                        """
                    {"access_token":"fresh-access","refresh_token":"rotated",
                    "token_type":"Bearer","expires_in":3600}
                        """.trimIndent().toByteArray(),
                    )
                },
            ) { 0 }
            val manager = OAuthTokenCoordinator(store, client) { 0 }
            CoordinatedOAuth2TokenProvider(account, configuration, manager, setOf(PRIMARY)).use { provider ->
                val outgoing = settings("smtp", smtpPort).copy(username = "alias@example.invalid")
                SmtpTransport(outgoing, sockets, provider).checkSettings()
            }
            assertThat(calls.get()).isEqualTo(1)
            assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("rotated")
        }
    }

    @Test
    fun `foreground session routes OAuth through managed credentials and receives and sends over TLS`() {
        fixture { server, sockets, imapPort, smtpPort ->
            val user = server.setUser(PRIMARY, PRIMARY, "accepted-access")
            val incoming = settings("imap", imapPort)
            val outgoing = settings("smtp", smtpPort)
            val registry = registry(incoming, outgoing)
            MailDatabase(directory.root.toPath().resolve("session.sqlite")).use { database ->
                val accounts = UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore(), registry)
                val metadata = UtMailAccount(
                    account.accountId,
                    "Controlled",
                    PRIMARY,
                    incoming,
                    outgoing,
                    account.credentialKey,
                    "controlled",
                )
                accounts.createWithOAuth(metadata, OAuthTokens("expired", "refresh", 0))
                val calls = AtomicInteger()
                val coordinator = sessionCoordinator(accounts, calls)
                val testSubject = ForegroundMailSessionFactory(
                    accounts,
                    database,
                    sockets,
                    silentLogger(),
                    directory.root.toPath().resolve("session-mime"),
                    OAuthMailAuthentication(registry, coordinator),
                )
                val raw = "From: $PRIMARY\r\nTo: $PRIMARY\r\nSubject: OAuth session\r\n\r\nControlled body"
                user.deliver(
                    jakarta.mail.internet.MimeMessage(
                        jakarta.mail.Session.getInstance(java.util.Properties()),
                        raw.byteInputStream(),
                    ),
                )
                testSubject.open(metadata.id).use { session ->
                    session.checkSettings()
                    session.refreshFolders()
                    session.sync("INBOX")
                    session.send(com.fsck.k9.mail.internet.MimeMessage.parseMimeMessage(raw.byteInputStream(), true))
                }
                assertThat(calls.get()).isEqualTo(1)
                val inbox = PersistentBackendStorage(database, metadata.id.toString()).getFolder("INBOX")
                assertThat(inbox.getMessageServerIds().size).isEqualTo(1)
                assertThat(inbox.readMessage(inbox.getMessageServerIds().single())!!.subject).isEqualTo("OAuth session")
                assertThat(server.waitForIncomingEmail(5000, 2)).isTrue()
                assertThat(
                    OAuthTokenStore(ManagedOAuthCredentialStore(accounts)).read(account, configuration)!!.refreshToken,
                ).isEqualTo("rotated")
                val unavailable = ForegroundMailSessionFactory(accounts, database, sockets, silentLogger())
                assertThat(runCatching { unavailable.open(metadata.id) }.isFailure).isTrue()
            }
        }
    }

    private fun registry(incoming: ServerSettings, outgoing: ServerSettings) = OAuthProviderRegistry(
        listOf(
            OAuthProviderSpec(
                "controlled",
                configuration,
                OAuthMailServer(incoming.host, incoming.port, incoming.connectionSecurity),
                OAuthMailServer(outgoing.host, outgoing.port, outgoing.connectionSecurity),
            ),
        ),
    )

    private fun sessionCoordinator(accounts: UtAccountManager, calls: AtomicInteger) = OAuthTokenCoordinator(
        OAuthTokenStore(ManagedOAuthCredentialStore(accounts)),
        OAuthTokenClient(
            OAuthTokenHttp { _, _ ->
                calls.incrementAndGet()
                OAuthHttpReply(
                    200,
                    "application/json",
                    """{"access_token":"accepted-access","refresh_token":"rotated",
                        "token_type":"Bearer","expires_in":3600}""".toByteArray(),
                )
            },
        ) { 0 },
    ) { 0 }

    private fun silentLogger() = DefaultLogger(object : LogSink {
        override val level = LogLevel.VERBOSE
        override fun log(event: LogEvent) = error("OAuth session must not log authentication or mail data")
    })

    private fun fixture(
        expectImap: Boolean = true,
        checkMail: (GreenMail, CancellableSocketFactory, Int, Int) -> Unit,
    ) {
        val previous = BinaryTempFileBody.getTempDirectory()
        val server = GreenMail(
            arrayOf(
                ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
                ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP),
            ),
        )
        server.start()
        try {
            initializeMailRuntime(
                DefaultLogger(object : LogSink {
                    override val level = LogLevel.VERBOSE
                    override fun log(event: LogEvent) = error("OAuth engine must not log authentication data")
                }),
                directory.root.toPath().resolve("mime"),
            )
            withTls(server, expectImap, checkMail)
        } finally {
            server.stop()
            BinaryTempFileBody.setTempDirectory(previous)
        }
    }

    private fun withTls(
        server: GreenMail,
        expectImap: Boolean,
        checkMail: (GreenMail, CancellableSocketFactory, Int, Int) -> Unit,
    ) {
        val tls = StartTlsMaterial(directory.root.toPath())
        StartTlsGateway(true, server.imap.port, tls.context).use { imap ->
            StartTlsGateway(false, server.smtp.port, tls.context).use { smtp ->
                CancellableSocketFactory(JvmTrustedSocketFactory(systemTrustManager(tls.trust))).use { sockets ->
                    checkMail(server, sockets, imap.port, smtp.port)
                    assertThat(smtp.upgrades.get() >= 1).isTrue()
                    if (expectImap) assertThat(imap.upgrades.get() >= 1).isTrue()
                }
            }
        }
    }

    private fun settings(type: String, port: Int) = ServerSettings(
        type = type,
        host = "localhost",
        port = port,
        connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED,
        authenticationType = AuthType.XOAUTH2,
        username = PRIMARY,
        password = null,
        clientCertificateAlias = null,
        extra = emptyMap(),
    )

    private object Config : ImapStoreConfig {
        override val logLabel = "ControlledOAuth"
        override fun isSubscribedFoldersOnly() = false
        override fun isExpungeImmediately() = false
        override fun clientInfo() = ImapClientInfo("Controlled OAuth fixture", "1")
    }
}

private const val PRIMARY = "primary@example.invalid"
