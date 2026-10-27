package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.ConnectionSecurity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory

/** Frozen 0.1.25 native driver. All identities belong to an isolated private fixture database. */
@Suppress("TooManyFunctions") // One owned prepare/reopen/cleanup fixture with deliberately explicit safety checks.
object ConfinedOAuthIdentityProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size in 3..4)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(args[0] == PROFILE && label == "$PROFILE (enforce)") { "Required application confinement unavailable" }
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        when (args[1]) {
            "prepare" -> {
                require(args.size == 4)
                Files.createDirectories(
                    parent,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                val directory = Files.createTempDirectory(
                    parent,
                    "oauth-identity-runtime-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                val policy = OAuthIdentityPolicy(args[2])
                Files.writeString(directory.resolve("profile-endpoint.txt"), policy.endpoint)
                Files.setPosixFilePermissions(
                    directory.resolve("profile-endpoint.txt"),
                    PosixFilePermissions.fromString("rw-------"),
                )
                try {
                    prepare(directory, policy, Path.of(args[3]))
                    println("Native profile verification/protected identity and legacy formats: PASS")
                    println("FIXTURE_DIR=$directory")
                } catch (failure: Throwable) {
                    try {
                        cleanup(directory)
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                        println("Identity fixture retained for protected cleanup: ${directory.fileName}")
                    }
                    throw failure
                }
            }

            "reopen" -> {
                val directory = requireIdentityFixtureDirectory(parent, Path.of(args[2]))
                reopen(directory)
                cleanup(directory)
                println("Fresh-process protected identity recovery and exact fixture cleanup: PASS")
            }

            "cleanup" -> {
                cleanup(requireIdentityFixtureDirectory(parent, Path.of(args[2])))
                println("Retained native identity fixture cleanup: PASS")
            }

            else -> error("Unknown identity fixture mode")
        }
    }

    private suspend fun prepare(directory: Path, policy: OAuthIdentityPolicy, certificate: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val raw = SignonCredentialStore(SignonIdentityIndex(database))
            val providers = registry(policy)
            val owner = UtAccountManager(AccountSettingsStorage(database), raw, providers)
            val account = providers.candidate("controlled", "Native identity fixture", EMAIL, null)
            val tokens = OAuthTokens("identity-access", "identity-refresh", 500000, setOf("mail"))
            check(runCatching { owner.createWithOAuth(account, tokens) }.exceptionOrNull() is OAuthIdentityException)
            JdkOAuthTokenHttp().use { untrusted ->
                check(runCatching { HttpsOAuthIdentityVerifier(untrusted).verify(policy, tokens) }.isFailure)
            }
            val verified = JdkOAuthTokenHttp(trustedContext(certificate)).use {
                HttpsOAuthIdentityVerifier(it).verify(policy, tokens)
            }
            owner.createWithOAuth(account, tokens, verified)
            val grant = OAuthGrant(account.id, account.credentialKey)
            val store = OAuthTokenStore(ManagedOAuthCredentialStore(owner))
            val requirement = providers.identityRequirementFor(account)
            store.read(grant, configuration())!!.requireIdentity(requirement)
            val coordinator = OAuthTokenCoordinator(
                store,
                OAuthTokenClient(
                    OAuthTokenHttp { _, _ ->
                        OAuthHttpReply(
                            200,
                            "application/json",
                            """{"access_token":"identity-rotated",
                    "refresh_token":"identity-refresh-rotated","token_type":"Bearer",
                    "expires_in":3600}""".toByteArray(),
                        )
                    },
                ) { 0 },
            ) { 0 }
            coordinator.invalidate(grant, configuration(), "identity-access")
            check(coordinator.token(grant, configuration(), setOf("mail"), requirement) == "identity-rotated")
            check(store.read(grant, configuration())!!.identity!!.subject == SUBJECT)
            maximumPayload(raw, policy)
            legacyPayloads(raw, policy)
            val sql = String(Files.readAllBytes(directory.resolve("mail.sqlite")), Charsets.ISO_8859_1)
            check(SUBJECT !in sql && "identity-refresh" !in sql)
        }
    }

    private fun maximumPayload(raw: CredentialStore, policy: OAuthIdentityPolicy) {
        val grant = OAuthGrant(AccountIdFactory.create())
        val store = OAuthTokenStore(raw)
        val scopes = (0..63).map { "s" + it.toString().padStart(2, '0') + "x".repeat(45) }.toSet()
        val tokens = OAuthTokens("A".repeat(16384), "R".repeat(16384), 1, scopes)
        val email = "𐐀".repeat(80)
        val identity = OAuthVerifiedIdentity.fromProfile(policy, "s".repeat(255), email, tokens).binding
        try {
            store.write(grant, configuration(), tokens.withIdentity(identity))
            val bytes = checkNotNull(raw.read(grant.credentialKey))
            try {
                check(bytes.size == 39997)
            } finally {
                bytes.fill(0)
            }
            store.read(grant, configuration())!!.requireIdentity(OAuthIdentityRequirement(policy, email))
        } finally {
            store.delete(grant)
        }
    }

    private fun legacyPayloads(raw: CredentialStore, policy: OAuthIdentityPolicy) {
        val grant = OAuthGrant(AccountIdFactory.create())
        val store = OAuthTokenStore(raw)
        try {
            for (version in listOf(2, 3)) {
                val scopes = if (version == 3) setOf("mail") else null
                store.write(grant, configuration(), OAuthTokens("legacy-access", "legacy-refresh", 1, scopes))
                val current = checkNotNull(raw.read(grant.credentialKey))
                val legacy = current.copyOf(current.size - if (version == 3) 1 else 9).also { it[3] = version.toByte() }
                try {
                    raw.write(grant.credentialKey, legacy)
                } finally {
                    current.fill(0)
                    legacy.fill(0)
                }
                val restored = checkNotNull(store.read(grant, configuration()))
                check(restored.identity == null && restored.scopes == scopes)
                check(
                    runCatching { restored.requireIdentity(OAuthIdentityRequirement(policy, EMAIL)) }
                        .exceptionOrNull() is OAuthIdentityException,
                )
            }
        } finally {
            store.delete(grant)
        }
    }

    private suspend fun reopen(directory: Path) {
        val policy = OAuthIdentityPolicy(Files.readString(directory.resolve("profile-endpoint.txt")))
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val providers = registry(policy)
            val owner =
                UtAccountManager(
                    AccountSettingsStorage(database),
                    SignonCredentialStore(SignonIdentityIndex(database)),
                    providers,
                )
            val account = owner.findAll().single()
            check(account.address == EMAIL)
            val store = OAuthTokenStore(ManagedOAuthCredentialStore(owner))
            val coordinator = OAuthTokenCoordinator(
                store,
                OAuthTokenClient(
                    OAuthTokenHttp { _, _ ->
                        error("Fresh-process fixture must not request a token")
                    },
                ),
            ) { 0 }
            check(
                coordinator.token(
                    OAuthGrant(account.id, account.credentialKey),
                    configuration(),
                    setOf("mail"),
                    providers.identityRequirementFor(account),
                ) == "identity-rotated",
            )
        }
    }

    private fun cleanup(directory: Path) {
        MailDatabase(directory.resolve("mail.sqlite")).use { database ->
            val raw = SignonCredentialStore(SignonIdentityIndex(database))
            val keys = database.query("SELECT key FROM signon_identity_index") { getText(0) }
            keys.forEach(raw::delete)
            check(database.query("SELECT key FROM signon_identity_index") { getText(0) }.isEmpty())
        }
        listOf("mail.sqlite", "mail.sqlite-wal", "mail.sqlite-shm", "profile-endpoint.txt").forEach {
            Files.deleteIfExists(directory.resolve(it))
        }
        Files.delete(directory)
    }

    private fun registry(policy: OAuthIdentityPolicy) = OAuthProviderRegistry(
        listOf(
            OAuthProviderSpec(
                "controlled",
                configuration(),
                OAuthMailServer("imap.example.invalid", 993, ConnectionSecurity.SSL_TLS_REQUIRED),
                OAuthMailServer("smtp.example.invalid", 587, ConnectionSecurity.STARTTLS_REQUIRED),
                requiredScopes = setOf("mail"),
                identityPolicy = policy,
            ),
        ),
    )

    private fun configuration() = OAuthConfiguration(
        "synthetic-identity-client",
        listOf("mail"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )

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

    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.25"
    private const val SUBJECT = "native-profile-subject"
    private const val EMAIL = "fixture@example.invalid"
}

internal fun requireIdentityFixtureDirectory(parent: Path, directory: Path): Path {
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    val real = directory.toRealPath()
    require(real.parent == parent.toRealPath() && real.fileName.toString().startsWith("oauth-identity-runtime-"))
    return real
}
