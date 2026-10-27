package com.fsck.k9.mail.transport.smtp

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import com.fsck.k9.mail.testing.security.TestTrustedSocketFactory
import com.fsck.k9.mail.transport.mockServer.MockSmtpServer
import java.util.Base64
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test

class SmtpOAuthAliasRefreshTest {
    @Before
    fun setup() {
        Log.logger = TestLogger()
    }

    @Test
    fun `verified primary can authenticate with refreshed token after all old token identities fail`() {
        val provider = Tokens()
        val server = script().apply {
            oldIdentities()
            expect(auth(ALIAS, "new"))
            output("535 alias new rejected")
            expect(auth(PRIMARY, "new"))
            output("235 authenticated")
            quit()
        }
        run(server, provider) { it.checkSettings() }
        assertThat(provider.invalidations).isEqualTo(1)
        assertThat(provider.requests).isEqualTo(4)
    }

    @Test
    fun `all identities failing with refreshed token invalidates once more without another retry wave`() {
        val provider = Tokens()
        val server = script().apply {
            oldIdentities()
            expect(auth(ALIAS, "new"))
            output("535 alias new rejected")
            expect(auth(PRIMARY, "new"))
            output("535 primary new rejected")
            quit()
        }
        run(server, provider) { transport ->
            assertFailure { transport.checkSettings() }.isInstanceOf<AuthenticationFailedException>()
        }
        assertThat(provider.invalidations).isEqualTo(2)
        assertThat(provider.requests).isEqualTo(4)
    }

    @Test
    fun `temporary failure for refreshed primary propagates without invalidating new token`() {
        val provider = Tokens()
        val server = script().apply {
            oldIdentities()
            expect(auth(ALIAS, "new"))
            output("535 alias new rejected")
            expect(auth(PRIMARY, "new"))
            output("454 temporary authentication failure")
            quit()
        }
        run(server, provider) { transport ->
            assertFailure { transport.checkSettings() }.isInstanceOf<NegativeSmtpReplyException>()
        }
        assertThat(provider.invalidations).isEqualTo(1)
        assertThat(provider.requests).isEqualTo(4)
    }

    private fun script() = MockSmtpServer().apply {
        output("220 controlled fixture")
        expect("EHLO $SMTP_HELLO_NAME")
        output("250-localhost")
        output("250 AUTH XOAUTH2")
    }

    private fun MockSmtpServer.oldIdentities() {
        expect(auth(ALIAS, "old"))
        output("535 alias old rejected")
        expect(auth(PRIMARY, "old"))
        output("535 primary old rejected")
    }

    private fun MockSmtpServer.quit() {
        expect("QUIT")
        output("221 goodbye")
    }

    private fun run(server: MockSmtpServer, provider: Tokens, check: (SmtpTransport) -> Unit) {
        server.start()
        try {
            val settings = ServerSettings(
                type = "smtp",
                host = server.host,
                port = server.port,
                connectionSecurity = ConnectionSecurity.NONE,
                authenticationType = AuthType.XOAUTH2,
                username = ALIAS,
                password = null,
                clientCertificateAlias = null,
            )
            check(SmtpTransport(settings, TestTrustedSocketFactory, provider))
            server.verifyConnectionClosed()
            server.verifyInteractionCompleted()
        } finally {
            server.shutdown()
        }
    }

    private class Tokens : OAuth2TokenProvider {
        var invalidations = 0
        var requests = 0
        override val usernames: Set<String> = setOf(PRIMARY)
        override fun getToken(timeoutMillis: Long): String {
            requests++
            return if (invalidations == 0) "old" else "new"
        }
        override fun invalidateToken() {
            invalidations++
        }
    }
}

private fun auth(username: String, token: String): String {
    val bytes = "user=$username\u0001auth=Bearer $token\u0001\u0001".toByteArray(Charsets.UTF_8)
    return "AUTH XOAUTH2 ${Base64.getEncoder().encodeToString(bytes)}"
}

private const val ALIAS = "alias@example.invalid"
private const val PRIMARY = "primary@example.invalid"
