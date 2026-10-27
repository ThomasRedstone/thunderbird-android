package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthenticationFailedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.awaitCancellation
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class CoordinatedOAuth2TokenProviderTest {
    private val account = OAuthGrant(AccountIdFactory.create())
    private val configuration = OAuthConfiguration(
        "synthetic",
        listOf("mail"),
        "https://login.example.invalid/auth",
        "https://login.example.invalid/token",
        "http://127.0.0.1:49152/callback",
    )
    private val credentials = FakeCredentialStore()
    private val store = OAuthTokenStore(credentials)
    private val calls = AtomicInteger()
    private val coordinator = OAuthTokenCoordinator(
        store,
        OAuthTokenClient(
            OAuthTokenHttp { _, _ ->
                val index = calls.incrementAndGet()
                OAuthHttpReply(
                    200,
                    "application/json",
                    """
            {"access_token":"access-$index","refresh_token":"refresh-$index",
            "token_type":"Bearer","expires_in":3600}
                    """.trimIndent().toByteArray(),
                )
            },
        ) { 0 },
    ) { 0 }

    @Test
    fun `separate connection providers share refresh but invalidate only their own issued token`() {
        store.write(account, configuration, OAuthTokens("old-access", "old-refresh", 500000))
        provider().use { first ->
            provider().use { second ->
                assertThat(first.getToken(1000)).isEqualTo("old-access")
                assertThat(second.getToken(1000)).isEqualTo("old-access")
                second.invalidateToken()
                assertThat(second.getToken(1000)).isEqualTo("access-1")
                first.invalidateToken()
                assertThat(first.getToken(1000)).isEqualTo("access-1")
                assertThat(calls.get()).isEqualTo(1)
            }
        }
    }

    @Test
    fun `no issued token means invalidation cannot expire saved authorization`() {
        store.write(account, configuration, OAuthTokens("fresh", "refresh", 500000))
        provider().use { testSubject ->
            testSubject.invalidateToken()
            assertThat(testSubject.getToken(1000)).isEqualTo("fresh")
            assertThat(calls.get()).isEqualTo(0)
        }
    }

    @Test
    fun `timeout cancels suspended refresh and provider close cancels another active request`() {
        val entered = CountDownLatch(1)
        val manager = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    entered.countDown()
                    awaitCancellation()
                },
            ),
        )
        store.write(account, configuration, OAuthTokens("expired", "refresh", 0))
        CoordinatedOAuth2TokenProvider(account, configuration, manager).use { testSubject ->
            val failure = runCatching { testSubject.getToken(300) }.exceptionOrNull() as AuthenticationFailedException
            assertThat(failure.message).isEqualTo("OAuth request cancelled or timed out")
            check(entered.count == 0L)
        }
        val active = CountDownLatch(1)
        val activeManager = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    active.countDown()
                    awaitCancellation()
                },
            ),
        )
        CoordinatedOAuth2TokenProvider(account, configuration, activeManager).use { testSubject ->
            val pending = CompletableFuture.supplyAsync { runCatching { testSubject.getToken(10000) } }
            check(active.await(2, TimeUnit.SECONDS))
            testSubject.close()
            assertThat(pending.get(2, TimeUnit.SECONDS).exceptionOrNull() is AuthenticationFailedException).isTrue()
        }
    }

    @Test
    fun `interruption cancels request and restores the calling thread interrupt flag`() {
        val entered = CountDownLatch(1)
        val manager = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    entered.countDown()
                    awaitCancellation()
                },
            ),
        )
        store.write(account, configuration, OAuthTokens("expired", "refresh", 0))
        CoordinatedOAuth2TokenProvider(account, configuration, manager).use { testSubject ->
            val result = AtomicReference<Pair<Throwable?, Boolean>>()
            val worker = Thread {
                val failure = runCatching { testSubject.getToken(10000) }.exceptionOrNull()
                result.set(failure to Thread.currentThread().isInterrupted)
            }
            worker.start()
            try {
                check(entered.await(2, TimeUnit.SECONDS))
                worker.interrupt()
                worker.join(2000)
                check(!worker.isAlive)
                assertThat(result.get().first is AuthenticationFailedException).isTrue()
                assertThat(result.get().second).isTrue()
            } finally {
                testSubject.close()
                worker.join(2000)
            }
        }
    }

    @Test
    fun `UI thread invalid timeout closed provider and missing authorization fail without credential leakage`() {
        provider().use { testSubject ->
            val uiError = AtomicReference<Throwable?>()
            SwingUtilities.invokeAndWait { uiError.set(runCatching { testSubject.getToken(1000) }.exceptionOrNull()) }
            assertThat(uiError.get() is AuthenticationFailedException).isTrue()
            for (timeout in listOf(0L, -1L, 30001L)) {
                assertThat(
                    runCatching {
                        testSubject.getToken(timeout)
                    }.exceptionOrNull() is AuthenticationFailedException,
                ).isTrue()
            }
            val missing = runCatching { testSubject.getToken(1000) }.exceptionOrNull() as AuthenticationFailedException
            assertThat(missing.cause).isNull()
            assertThat(missing.messageFromServer).isNull()
            assertThat(missing.message).isEqualTo("OAuth token request: REAUTH_REQUIRED")
            testSubject.close()
            assertThat(
                runCatching {
                    testSubject.getToken(1000)
                }.exceptionOrNull() is AuthenticationFailedException,
            ).isTrue()
            assertThat(calls.get()).isEqualTo(0)
        }
    }

    @Test
    fun `locked service errors are sanitized and do not poison provider lifecycle`() {
        store.write(account, configuration, OAuthTokens("private-access", "private-refresh", 500000))
        provider().use { testSubject ->
            credentials.locked = true
            val failure = runCatching { testSubject.getToken(1000) }.exceptionOrNull() as AuthenticationFailedException
            assertThat(failure.message).isEqualTo("Protected OAuth storage: LOCKED")
            assertThat(failure.cause).isNull()
            assertThat(failure.suppressed.size).isEqualTo(0)
            credentials.locked = false
            assertThat(testSubject.getToken(1000)).isEqualTo("private-access")
        }
    }

    @Test
    fun `verified alternatives are copied and unsafe identity text is rejected`() {
        val names = mutableSetOf("verified@example.invalid")
        CoordinatedOAuth2TokenProvider(account, configuration, coordinator, names).use { testSubject ->
            names.clear()
            assertThat(testSubject.usernames).isEqualTo(setOf("verified@example.invalid"))
            assertThat(testSubject.toString()).isEqualTo("CoordinatedOAuth2TokenProvider(redacted)")
        }
        assertThat(
            runCatching {
                CoordinatedOAuth2TokenProvider(account, configuration, coordinator, setOf("unsafe\r\n@example.invalid"))
            }.exceptionOrNull() is IllegalArgumentException,
        ).isTrue()
    }

    @Test
    fun `timeout budget includes waiting behind an active call without cancelling that call`() {
        val entered = CountDownLatch(1)
        val manager = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    entered.countDown()
                    awaitCancellation()
                },
            ),
        )
        store.write(account, configuration, OAuthTokens("expired", "refresh", 0))
        CoordinatedOAuth2TokenProvider(account, configuration, manager).use { testSubject ->
            val first = CompletableFuture.supplyAsync { runCatching { testSubject.getToken(10000) } }
            check(entered.await(2, TimeUnit.SECONDS))
            try {
                val second = CompletableFuture.supplyAsync { runCatching { testSubject.getToken(100) } }
                val failure = second.get(2, TimeUnit.SECONDS).exceptionOrNull() as AuthenticationFailedException
                assertThat(failure.message).isEqualTo("OAuth request cancelled or timed out")
                assertThat(!first.isDone).isTrue()
            } finally {
                testSubject.close()
                first.get(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `shared provider keeps rejection tied to issuing authentication thread`() {
        store.write(account, configuration, OAuthTokens("old-access", "refresh", 500000))
        provider().use { testSubject ->
            val issued = CountDownLatch(1)
            val reject = CountDownLatch(1)
            val earlier = CompletableFuture.runAsync {
                check(testSubject.getToken(1000) == "old-access")
                issued.countDown()
                check(reject.await(3, TimeUnit.SECONDS))
                testSubject.invalidateToken()
            }
            check(issued.await(2, TimeUnit.SECONDS))
            try {
                assertThat(testSubject.getToken(1000)).isEqualTo("old-access")
                testSubject.invalidateToken()
                assertThat(testSubject.getToken(1000)).isEqualTo("access-1")
                reject.countDown()
                earlier.get(2, TimeUnit.SECONDS)
                assertThat(testSubject.getToken(1000)).isEqualTo("access-1")
                assertThat(calls.get()).isEqualTo(1)
            } finally {
                reject.countDown()
                testSubject.close()
                earlier.handle { _, _ -> Unit }.get(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `equal access text in another generation cannot be invalidated by old provider`() {
        val replacement = OAuthGrant(account.accountId)
        store.write(account, configuration, OAuthTokens("same-access", "old-refresh", 500000))
        store.write(replacement, configuration, OAuthTokens("same-access", "new-refresh", 500000))
        provider().use { earlier ->
            CoordinatedOAuth2TokenProvider(replacement, configuration, coordinator).use { current ->
                assertThat(earlier.getToken(1000)).isEqualTo("same-access")
                assertThat(current.getToken(1000)).isEqualTo("same-access")
                earlier.invalidateToken()
                assertThat(current.getToken(1000)).isEqualTo("same-access")
                assertThat(store.read(replacement, configuration)!!.refreshToken).isEqualTo("new-refresh")
                assertThat(calls.get()).isEqualTo(0)
            }
        }
    }

    private fun provider() = CoordinatedOAuth2TokenProvider(account, configuration, coordinator)
}
