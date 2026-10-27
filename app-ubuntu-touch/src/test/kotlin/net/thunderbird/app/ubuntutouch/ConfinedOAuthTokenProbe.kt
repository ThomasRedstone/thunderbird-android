package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.ConnectionSecurity
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.oauth.OAuthConfiguration

/** Test-only installed-runtime driver; no provider endpoint or production trust modification. */
object ConfinedOAuthTokenProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 3)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args[0]} (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        Files.createDirectories(parent)
        if (args[1] == "cleanup") {
            cleanupFixture(parent, Path.of(args[2]))
            println("Retained confined OAuth fixture cleanup: PASS")
            return@runBlocking
        }
        val endpoint = URI(args[1])
        requireTokenEndpoint(endpoint)
        val directory = Files.createTempDirectory(parent, "oauth-tokens-runtime-")
        var cleaned = false
        try {
            MailDatabase(directory.resolve("mail.sqlite")).use { database ->
                val raw = SignonCredentialStore(SignonIdentityIndex(database))
                val configuration = OAuthConfiguration(
                    "synthetic-client",
                    listOf("mail"),
                    "https://login.example.invalid/auth",
                    endpoint.toASCIIString(),
                    "http://127.0.0.1:49152/callback",
                )
                val registry = registry(configuration)
                val owner = UtAccountManager(AccountSettingsStorage(database), raw, registry)
                try {
                    val context = trustedContext(Path.of(args[2]))
                    verify(owner, configuration, endpoint, context)
                    verifyAuthorization(owner, registry, context)
                    val text = String(Files.readAllBytes(directory.resolve("mail.sqlite")), Charsets.ISO_8859_1)
                    check("A".repeat(PAYLOAD_LENGTH) !in text && "controlled-refresh-1" !in text)
                } finally {
                    owner.findAll().forEach { owner.delete(it.id) }
                    cleaned = owner.cleanupUnusedCredentials() &&
                        database.query("SELECT identity_id FROM signon_identity_index") { getLong(0) }.isEmpty()
                }
            }
        } finally {
            if (cleaned) {
                check(directory.toFile().deleteRecursively())
            } else {
                println("OAuth fixture retained for protected cleanup: ${directory.fileName}")
            }
        }
        println("Enforcing native large protected payload, HTTPS refresh/authorization and identity cleanup: PASS")
    }

    private suspend fun verify(
        owner: UtAccountManager,
        configuration: OAuthConfiguration,
        endpoint: URI,
        context: SSLContext,
    ) {
        val account = registry(configuration).candidate(
            "controlled",
            "Native controlled fixture",
            "fixture@example.invalid",
            null,
        )
        owner.createWithOAuth(account, OAuthTokens("A".repeat(PAYLOAD_LENGTH), "R".repeat(PAYLOAD_LENGTH), 0))
        val grant = OAuthGrant(account.id, account.credentialKey)
        val store = OAuthTokenStore(ManagedOAuthCredentialStore(owner))
        check(store.read(grant, configuration)!!.accessToken.length == PAYLOAD_LENGTH)
        check(store.read(grant, configuration)!!.refreshToken!!.length == PAYLOAD_LENGTH)
        JdkOAuthTokenHttp().use { untrusted ->
            val failure = runCatching {
                untrusted.post(endpoint, mapOf("grant_type" to "refresh_token"))
            }.exceptionOrNull()
            check(failure is OAuthTokenException && failure.reason == OAuthTokenFailure.UNAVAILABLE)
        }
        JdkOAuthTokenHttp(context).use { http ->
            val coordinator = OAuthTokenCoordinator(store, OAuthTokenClient(http))
            check(coordinator.token(grant, configuration) == "controlled-access-1")
            check(coordinator.token(grant, configuration) == "controlled-access-1")
            val saved = store.read(grant, configuration)!!
            check(saved.refreshToken == "controlled-refresh-1" && saved.expiresAtMillis > System.currentTimeMillis())
        }
    }

    private fun registry(configuration: OAuthConfiguration) = OAuthProviderRegistry(
        listOf(
            provider("controlled", configuration),
            provider(
                "authorization",
                configuration.copy(
                    tokenEndpoint = URI(configuration.tokenEndpoint).resolve("/exchange").toASCIIString(),
                ),
            ),
        ),
    )

    private fun provider(id: String, configuration: OAuthConfiguration) = OAuthProviderSpec(
        id,
        configuration,
        OAuthMailServer("imap.example.invalid", 993, ConnectionSecurity.SSL_TLS_REQUIRED),
        OAuthMailServer("smtp.example.invalid", 587, ConnectionSecurity.STARTTLS_REQUIRED),
    )

    private suspend fun verifyAuthorization(
        owner: UtAccountManager,
        registry: OAuthProviderRegistry,
        context: SSLContext,
    ) {
        val candidate = registry.candidate(
            "authorization",
            "Native authorization fixture",
            "fixture@example.invalid",
            null,
        )
        val configuration = registry.configurationFor(candidate)
        JdkOAuthTokenHttp(context).use { http ->
            val authorization = OAuthAccountAuthorization(
                owner,
                registry,
                OAuthBrowser { uri ->
                    val fields = uri.rawQuery.split('&').associate {
                        val pair = it.split('=', limit = 2)
                        pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
                    }
                    val redirect = URI(fields.getValue("redirect_uri"))
                    Socket(redirect.host, redirect.port).use { socket ->
                        val target = "${redirect.path}?state=${fields.getValue("state")}" +
                            "&code=${fields.getValue("code_challenge")}"
                        socket.getOutputStream().write(
                            "GET $target HTTP/1.1\r\nHost: ${redirect.authority}\r\n\r\n".toByteArray(),
                        )
                    }
                },
                OAuthTokenClient(http),
            )
            check(authorization.prepare(candidate).authorize().accountId == candidate.id)
            val saved = OAuthTokenStore(ManagedOAuthCredentialStore(owner))
                .read(OAuthGrant(candidate.id, candidate.credentialKey), configuration)!!
            check(saved.accessToken == "controlled-access-2" && saved.refreshToken == "controlled-refresh-2")
        }
    }

    private fun cleanupFixture(parent: Path, directory: Path) {
        require(directory.toRealPath().parent == parent.toRealPath())
        require(directory.fileName.toString().startsWith("oauth-tokens-runtime-"))
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val owner = UtAccountManager(
                AccountSettingsStorage(database),
                SignonCredentialStore(SignonIdentityIndex(database)),
            )
            owner.findAll().forEach { owner.delete(it.id) }
            check(owner.cleanupUnusedCredentials())
            check(database.query("SELECT identity_id FROM signon_identity_index") { getLong(0) }.isEmpty())
        }
        check(directory.toFile().deleteRecursively())
    }

    private fun trustedContext(certificate: Path): SSLContext {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            Files.newInputStream(certificate).use {
                setCertificateEntry("controlled", CertificateFactory.getInstance("X.509").generateCertificate(it))
            }
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }
}

private const val PAYLOAD_LENGTH = 16_384
