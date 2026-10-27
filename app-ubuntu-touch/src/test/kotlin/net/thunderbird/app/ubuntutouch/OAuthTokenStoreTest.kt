package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class OAuthTokenStoreTest {
    private val account = OAuthGrant(AccountIdFactory.create())
    private val other = OAuthGrant(AccountIdFactory.create())
    private val configuration = OAuthConfiguration(
        "synthetic-client",
        listOf("read", "send"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )
    private val tokens = OAuthTokens("private-access", "private-refresh", 123456789L)

    @Test
    fun `maximum identity and scope metadata fit with maximum tokens and reopen without plaintext state`() {
        val scopes = (0..63).map { "s" + it.toString().padStart(2, '0') + "x".repeat(45) }.toSet()
        val base = OAuthTokens("a".repeat(16384), "r".repeat(16384), 1, scopes)
        val policy = OAuthIdentityPolicy("https://login.example.invalid/profile")
        val email = "𐐀".repeat(80)
        val binding = OAuthVerifiedIdentity.fromProfile(policy, "s".repeat(255), email, base).binding
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, base.withIdentity(binding))
        assertThat(credentials.values.getValue(account.credentialKey).size).isEqualTo(39997)
        val reopened = testSubject.read(account, configuration)!!
        reopened.requireIdentity(OAuthIdentityRequirement(policy, email))
        assertThat(reopened.identity!!.subject).isEqualTo("s".repeat(255))
    }

    @Test
    fun `legacy v3 scopes remain readable without inventing a verified identity`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, OAuthTokens("access", "refresh", 1, setOf("read")))
        val encoded = credentials.values.getValue(account.credentialKey)
        credentials.values[account.credentialKey] = encoded.copyOf(encoded.size - 1).also { it[3] = 3 }
        val reopened = testSubject.read(account, configuration)!!
        assertThat(reopened.scopes).isEqualTo(setOf("read"))
        assertThat(reopened.identity).isNull()
    }

    @Test
    fun `maximum tokens and bounded scope metadata fit the protected payload limit`() {
        val scopes = (0..5).map { "a".repeat(511) + it }.toSet()
        val scoped = OAuthTokens("a".repeat(16384), "r".repeat(16384), 1, scopes)
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, scoped)
        assertThat(credentials.values.getValue(account.credentialKey).size <= 40000).isTrue()
        assertThat(testSubject.read(account, configuration)!!.scopes).isEqualTo(scopes)
    }

    @Test
    fun `invalid scope counts duplicate entries and invalid syntax fail closed in protected metadata`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        val encoded = credentials.values.getValue(account.credentialKey)
        val prefix = encoded.copyOf(encoded.size - 9)
        val invalid = listOf<Pair<Int, List<String>>>(
            -2 to emptyList(),
            65 to emptyList(),
            1 to listOf(""),
            2 to listOf("read", "read"),
            1 to listOf("read send"),
        )
        invalid.forEach { (count, entries) ->
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).use {
                it.write(prefix)
                it.writeInt(count)
                entries.forEach(it::writeUTF)
                it.writeInt(-1)
                it.writeBoolean(false)
            }
            credentials.values[account.credentialKey] = bytes.toByteArray()
            assertInvalid { testSubject.read(account, configuration) }
        }
    }

    @Test
    fun `granted scope metadata snapshots input and survives protected reopen separately from original grant`() {
        val mutable = mutableSetOf("read")
        val scoped = OAuthTokens("access", "refresh", 1, mutable, setOf("read", "send"))
        mutable += "send"
        assertThat(scoped.scopes).isEqualTo(setOf("read"))
        val testSubject = OAuthTokenStore(RecordingCredentials())
        testSubject.write(account, configuration, scoped)
        val reopened = testSubject.read(account, configuration)!!
        assertThat(reopened.scopes).isEqualTo(setOf("read"))
        assertThat(reopened.grantScopes).isEqualTo(setOf("read", "send"))
        assertThat(runCatching { reopened.requireScopes(setOf("Read")) }.isFailure).isTrue()
        reopened.requireScopes(setOf("read"))
    }

    @Test
    fun `legacy v2 state remains readable without inventing permissions`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        val v3 = credentials.values.getValue(account.credentialKey)
        credentials.values[account.credentialKey] = v3.copyOf(v3.size - 9).also { it[3] = 2 }
        val legacy = testSubject.read(account, configuration)!!
        assertThat(legacy.refreshToken).isEqualTo(tokens.refreshToken)
        assertThat(legacy.scopes).isNull()
        assertThat(legacy.grantScopes).isNull()
        val failure = runCatching { legacy.requireScopes(setOf("read")) }.exceptionOrNull() as OAuthTokenException
        assertThat(failure.reason).isEqualTo(OAuthTokenFailure.INSUFFICIENT_SCOPE)
        testSubject.write(account, configuration, legacy)
        assertThat(credentials.values.getValue(account.credentialKey)[3]).isEqualTo(4.toByte())
    }

    @Test
    fun `protected state reopens through a new store and transient arrays are cleared`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        assertThat(credentials.lastWrite!!.all { it == 0.toByte() }).isTrue()
        val reopened = OAuthTokenStore(credentials).read(account, configuration)!!
        assertThat(reopened.accessToken).isEqualTo(tokens.accessToken)
        assertThat(reopened.refreshToken).isEqualTo(tokens.refreshToken)
        assertThat(reopened.expiresAtMillis).isEqualTo(tokens.expiresAtMillis)
        assertThat(credentials.lastRead!!.all { it == 0.toByte() }).isTrue()
        assertThat(reopened.toString()).isEqualTo("OAuthTokens(redacted)")
    }

    @Test
    fun `account isolation rejects even a copied protected payload`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        assertThat(testSubject.read(other, configuration)).isNull()
        credentials.values[other.credentialKey] = credentials.values.getValue(account.credentialKey).copyOf()
        assertInvalid { testSubject.read(other, configuration) }
        assertThat(testSubject.read(account, configuration)!!.accessToken).isEqualTo(tokens.accessToken)
    }

    @Test
    fun `provider client endpoints and scopes bind state while redirect port and scope order can change`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        listOf(
            configuration.copy(clientId = "another-client"),
            configuration.copy(authorizationEndpoint = "https://other.example.invalid/auth"),
            configuration.copy(tokenEndpoint = "https://other.example.invalid/token"),
            configuration.copy(scopes = listOf("read")),
        ).forEach { changed -> assertInvalid { testSubject.read(account, changed) } }
        val equivalent = configuration.copy(
            scopes = listOf("send", "read", "read"),
            redirectUri = "http://127.0.0.1:49153/callback",
        )
        assertThat(testSubject.read(account, equivalent)!!.refreshToken).isEqualTo(tokens.refreshToken)
    }

    @Test
    fun `missing refresh token and expired access state remain explicit`() {
        val testSubject = OAuthTokenStore(RecordingCredentials())
        testSubject.write(account, configuration, OAuthTokens("access-only", null, 0))
        val result = testSubject.read(account, configuration)!!
        assertThat(result.refreshToken).isNull()
        assertThat(result.expiresAtMillis).isEqualTo(0L)
    }

    @Test
    fun `truncation unknown version excess size trailing bytes and malformed refresh marker fail closed`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        val key = account.credentialKey
        val valid = credentials.values.getValue(key).copyOf()
        val refreshMarker = 4 + 32 + 2 + tokens.accessToken.length
        val invalid = listOf(
            valid.copyOf(3),
            valid.copyOf(valid.size - 1),
            valid.copyOf().also { it[3] = 0 },
            valid.copyOf().also { it[3] = 1 },
            valid + byteArrayOf(1),
            valid.copyOf().also { it[refreshMarker] = 2 },
            ByteArray(40001),
        )
        invalid.forEach { bytes ->
            credentials.values[key] = bytes
            assertInvalid { testSubject.read(account, configuration) }
            assertThat(credentials.lastRead!!.all { it == 0.toByte() }).isTrue()
        }
    }

    @Test
    fun `locked credential service remains an error and failed write clears its transient bytes`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        credentials.locked = true
        listOf<() -> Unit>(
            { testSubject.read(account, configuration) },
            { testSubject.write(account, configuration, tokens) },
            { testSubject.delete(account) },
        ).forEach { operation ->
            val failure = runCatching(operation).exceptionOrNull() as SecretStoreException
            assertThat(failure.reason).isEqualTo(SecretFailure.LOCKED)
        }
        assertThat(credentials.lastWrite!!.all { it == 0.toByte() }).isTrue()
        credentials.locked = false
        assertThat(testSubject.read(account, configuration)!!.refreshToken).isEqualTo(tokens.refreshToken)
    }

    @Test
    fun `deletion affects selected account only`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        testSubject.write(account, configuration, tokens)
        testSubject.write(other, configuration, OAuthTokens("other-access", "other-refresh", 0))
        testSubject.delete(account)
        assertThat(testSubject.read(account, configuration)).isNull()
        assertThat(testSubject.read(other, configuration)!!.accessToken).isEqualTo("other-access")
        testSubject.delete(account)
    }

    @Test
    fun `invalid token text length and expiry are rejected without exposing values`() {
        listOf<() -> OAuthTokens>(
            { OAuthTokens("", null, 0) },
            { OAuthTokens("access\r\nprivate", null, 0) },
            { OAuthTokens("access", "refresh\u0000private", 0) },
            { OAuthTokens("x".repeat(16385), null, 0) },
            { OAuthTokens("access", null, -1) },
        ).forEach {
            val failure = runCatching(it).exceptionOrNull()
            assertThat(failure is IllegalArgumentException).isTrue()
            assertThat(failure!!.message in setOf("Invalid OAuth token", "Invalid OAuth expiry")).isTrue()
        }
    }

    @Test
    fun `generations of same account isolate values deletion and copied payloads`() {
        val credentials = RecordingCredentials()
        val testSubject = OAuthTokenStore(credentials)
        val replacement = OAuthGrant(account.accountId)
        testSubject.write(account, configuration, tokens)
        testSubject.write(replacement, configuration, OAuthTokens("new-access", "new-refresh", 0))
        assertThat(testSubject.read(account, configuration)!!.accessToken).isEqualTo(tokens.accessToken)
        assertThat(testSubject.read(replacement, configuration)!!.accessToken).isEqualTo("new-access")
        val oldPayload = credentials.values.getValue(account.credentialKey).copyOf()
        testSubject.delete(account)
        assertThat(testSubject.read(account, configuration)).isNull()
        assertThat(testSubject.read(replacement, configuration)!!.refreshToken).isEqualTo("new-refresh")
        credentials.values[replacement.credentialKey] = oldPayload
        assertInvalid { testSubject.read(replacement, configuration) }
    }

    private fun assertInvalid(read: () -> OAuthTokens?) {
        val failure = runCatching(read).exceptionOrNull()
        assertThat(failure is OAuthTokenStorageException).isTrue()
        assertThat(failure?.cause).isNull()
        assertThat(failure?.suppressed?.size).isEqualTo(0)
    }

    private class RecordingCredentials : CredentialStore {
        val values = mutableMapOf<String, ByteArray>()
        var lastRead: ByteArray? = null
        var lastWrite: ByteArray? = null
        var locked = false
        override fun read(key: String): ByteArray? {
            checkAvailable()
            return values[key]?.copyOf()?.also { lastRead = it }
        }
        override fun write(key: String, value: ByteArray) {
            lastWrite = value
            checkAvailable()
            values[key] = value.copyOf()
        }
        override fun delete(key: String) {
            checkAvailable()
            values.remove(key)?.fill(0)
        }
        private fun checkAvailable() {
            if (locked) throw SecretStoreException(SecretFailure.LOCKED)
        }
    }
}
