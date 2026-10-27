package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class OAuthTokenCoordinatorTest {
    private val account = AccountIdFactory.create()
    private val other = AccountIdFactory.create()
    private val configuration = OAuthConfiguration(
        "synthetic",
        listOf("read", "send"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )
    private val credentials = Credentials()
    private val store = OAuthTokenStore(credentials)
    private val calls = AtomicInteger()
    private var now = 1000L

    @Test
    fun `fresh stored token is reused but exact safety margin triggers refresh`() = runBlocking {
        val testSubject = coordinator()
        store.write(account, configuration, OAuthTokens("fresh", "refresh", now + 60001))
        assertThat(testSubject.token(account, configuration)).isEqualTo("fresh")
        assertThat(calls.get()).isEqualTo(0)
        now++
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-1")
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("refresh-1")
    }

    @Test
    fun `concurrent same account operations perform one refresh and reuse persisted rotation`() = runBlocking {
        val entered = Channel<Unit>(1)
        val release = CompletableDeferred<Unit>()
        val testSubject = coordinator { _, _ ->
            entered.send(Unit)
            release.await()
            success(calls.incrementAndGet())
        }
        expired(account)
        val operations = List(12) { async(Dispatchers.Default) { testSubject.token(account, configuration) } }
        entered.receive()
        release.complete(Unit)
        assertThat(withTimeout(3000) { operations.awaitAll() }.toSet()).isEqualTo(setOf("access-1"))
        assertThat(calls.get()).isEqualTo(1)
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("refresh-1")
        assertThat(coordinator().token(account, configuration)).isEqualTo("access-1")
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `different accounts refresh independently without a global network lock`() = runBlocking {
        val entered = Channel<Unit>(2)
        val release = CompletableDeferred<Unit>()
        val testSubject = coordinator { _, parameters ->
            entered.send(Unit)
            release.await()
            val name = parameters.getValue("refresh_token")
            OAuthHttpReply(
                200,
                "application/json",
                """{"access_token":"$name","token_type":"Bearer","expires_in":3600}""".toByteArray(),
            )
        }
        expired(account)
        store.write(other, configuration, OAuthTokens("other", "other-refresh", 0))
        val first = async { testSubject.token(account, configuration) }
        val second = async { testSubject.token(other, configuration) }
        withTimeout(3000) { repeat(2) { entered.receive() } }
        release.complete(Unit)
        assertThat(first.await()).isEqualTo("old-refresh")
        assertThat(second.await()).isEqualTo("other-refresh")
    }

    @Test
    fun `failed rotation persistence retries the protected write without repeating refresh`() = runBlocking {
        expired(account)
        val testSubject = coordinator { _, _ ->
            credentials.failWrites = true
            success(calls.incrementAndGet())
        }
        val failure = runCatching { testSubject.token(account, configuration) }.exceptionOrNull()
        assertThat(failure is SecretStoreException).isTrue()
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("old-refresh")
        credentials.failWrites = false
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-1")
        assertThat(calls.get()).isEqualTo(1)
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("refresh-1")
    }

    @Test
    fun `pending rotation cannot be written under another provider configuration`() = runBlocking {
        expired(account)
        val testSubject = coordinator { _, _ ->
            credentials.failWrites = true
            success(calls.incrementAndGet())
        }
        runCatching { testSubject.token(account, configuration) }
        credentials.failWrites = false
        val failure = runCatching {
            testSubject.token(account, configuration.copy(clientId = "other-client"))
        }.exceptionOrNull()
        assertThat(failure is OAuthTokenStorageException).isTrue()
        val equivalent = configuration.copy(
            scopes = listOf("send", "read"),
            redirectUri = "http://127.0.0.1:49153/callback",
        )
        assertThat(testSubject.token(account, equivalent)).isEqualTo("access-1")
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `delayed rejection of old token cannot invalidate a new token`() = runBlocking {
        val testSubject = coordinator()
        expired(account)
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-1")
        testSubject.invalidate(account, configuration, "old-access")
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-1")
        assertThat(calls.get()).isEqualTo(1)
        testSubject.invalidate(account, configuration, "access-1")
        assertThat(store.read(account, configuration)!!.expiresAtMillis).isEqualTo(0L)
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-2")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `invalid grant removes unusable state and does not retry on every operation`() = runBlocking {
        expired(account)
        val testSubject = coordinator { _, _ ->
            calls.incrementAndGet()
            OAuthHttpReply(400, "application/json", """{"error":"invalid_grant"}""".toByteArray())
        }
        repeat(2) {
            val failure = runCatching {
                testSubject.token(account, configuration)
            }.exceptionOrNull() as OAuthTokenException
            assertThat(failure.reason).isEqualTo(OAuthTokenFailure.REAUTH_REQUIRED)
        }
        assertThat(calls.get()).isEqualTo(1)
        assertThat(store.read(account, configuration)).isNull()
    }

    @Test
    fun `cancelling a queued waiter leaves the in flight refresh usable`() = runBlocking {
        expired(account)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val testSubject = coordinator { _, _ ->
            entered.complete(Unit)
            release.await()
            success(calls.incrementAndGet())
        }
        val first = async { testSubject.token(account, configuration) }
        entered.await()
        val waiter =
            async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { testSubject.token(account, configuration) }
        waiter.cancelAndJoin()
        release.complete(Unit)
        assertThat(first.await()).isEqualTo("access-1")
        assertThat(waiter.isCancelled).isTrue()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `observed rotation finishes protected persistence despite cancellation`() = runBlocking {
        expired(account)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        credentials.onWrite = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val testSubject = coordinator()
        val pending = async { testSubject.token(account, configuration) }
        withContext(Dispatchers.IO) { check(entered.await(3, TimeUnit.SECONDS)) }
        pending.cancel()
        release.countDown()
        withTimeout(3000) { pending.join() }
        credentials.onWrite = null
        assertThat(pending.isCancelled).isTrue()
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("refresh-1")
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-1")
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `deletion clears pending rotation and locked service never falls back to memory`() = runBlocking {
        val testSubject = coordinator()
        testSubject.storeGrant(account, configuration, OAuthTokens("fresh", "refresh", 500000))
        credentials.locked = true
        assertThat(
            runCatching {
                testSubject.token(account, configuration)
            }.exceptionOrNull() is SecretStoreException,
        ).isTrue()
        credentials.locked = false
        assertThat(calls.get()).isEqualTo(0)
        testSubject.invalidate(account, configuration, "fresh")
        credentials.failWrites = true
        assertThat(
            runCatching {
                testSubject.token(account, configuration)
            }.exceptionOrNull() is SecretStoreException,
        ).isTrue()
        credentials.failWrites = false
        testSubject.delete(account)
        val failure = runCatching { testSubject.token(account, configuration) }.exceptionOrNull() as OAuthTokenException
        assertThat(failure.reason).isEqualTo(OAuthTokenFailure.REAUTH_REQUIRED)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `failed invalidation persistence never revives rejected access token`() = runBlocking {
        val testSubject = coordinator()
        testSubject.storeGrant(account, configuration, OAuthTokens("rejected-access", "refresh", 500000))
        credentials.failWrites = true
        val failure = runCatching {
            testSubject.invalidate(account, configuration, "rejected-access")
        }.exceptionOrNull()
        assertThat(failure is SecretStoreException).isTrue()
        credentials.failWrites = false
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-1")
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `rotation is saved but already expired replacement is never returned`() = runBlocking {
        expired(account)
        val testSubject = coordinator { _, _ ->
            val number = calls.incrementAndGet()
            if (number == 1) now += 4000000
            success(number)
        }
        val failure = runCatching { testSubject.token(account, configuration) }.exceptionOrNull()
        assertThat(failure is OAuthTokenException).isTrue()
        assertThat((failure as OAuthTokenException).reason).isEqualTo(OAuthTokenFailure.UNAVAILABLE)
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("refresh-1")
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-2")
    }

    @Test
    fun `transient endpoint failure preserves refresh state without returning expired access`() = runBlocking {
        expired(account)
        var unavailable = true
        val testSubject = coordinator { _, _ ->
            val number = calls.incrementAndGet()
            if (unavailable) OAuthHttpReply(503, "text/plain", "temporary".toByteArray()) else success(number)
        }
        val failure = runCatching { testSubject.token(account, configuration) }.exceptionOrNull() as OAuthTokenException
        assertThat(failure.reason).isEqualTo(OAuthTokenFailure.UNAVAILABLE)
        assertThat(store.read(account, configuration)!!.refreshToken).isEqualTo("old-refresh")
        unavailable = false
        assertThat(testSubject.token(account, configuration)).isEqualTo("access-2")
        assertThat(calls.get()).isEqualTo(2)
    }

    private fun expired(id: net.thunderbird.feature.account.AccountId) {
        store.write(id, configuration, OAuthTokens("old-access", "old-refresh", 0))
    }

    private fun coordinator(
        http: OAuthTokenHttp = OAuthTokenHttp { _, _ ->
            success(calls.incrementAndGet())
        },
    ): OAuthTokenCoordinator =
        OAuthTokenCoordinator(store, OAuthTokenClient(http) { now }) { now }

    private fun success(index: Int): OAuthHttpReply = OAuthHttpReply(
        200,
        "application/json",
        """
            {"access_token":"access-$index","refresh_token":"refresh-$index",
            "token_type":"Bearer","expires_in":3600}
        """.trimIndent().toByteArray(),
    )

    private class Credentials : CredentialStore {
        private val values = ConcurrentHashMap<String, ByteArray>()

        @Volatile var failWrites = false

        @Volatile var locked = false

        @Volatile var onWrite: (() -> Unit)? = null
        override fun read(key: String): ByteArray? {
            checkAvailable()
            return values[key]?.copyOf()
        }
        override fun write(key: String, value: ByteArray) {
            checkAvailable()
            if (failWrites) throw SecretStoreException(SecretFailure.UNAVAILABLE)
            onWrite?.invoke()
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
