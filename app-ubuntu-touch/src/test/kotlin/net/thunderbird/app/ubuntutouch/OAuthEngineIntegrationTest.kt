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
