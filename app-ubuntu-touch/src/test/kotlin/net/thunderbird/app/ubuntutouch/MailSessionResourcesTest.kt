package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailSessionResourcesTest {
    @Test
    fun `operation closes all resources despite one teardown failure and rejects late ownership`() {
        val testSubject = MailOperation()
        var closed = 0
        testSubject.register(AutoCloseable { error("Controlled close failure") })
        testSubject.register(AutoCloseable { closed++ })
        assertThat(runCatching { testSubject.close() }.isFailure).isTrue()
        assertThat(closed).isEqualTo(1)
        assertThat(runCatching { testSubject.register(AutoCloseable { closed++ }) }.isFailure).isTrue()
        assertThat(closed).isEqualTo(2)
        testSubject.close()
    }

    @Test
    fun `session resource close is idempotent and immediately closes late additions`() {
        val testSubject = MailSessionResources(CancellableSocketFactory(JvmTrustedSocketFactory()))
        var closed = 0
        testSubject.own(AutoCloseable { closed++ })
        testSubject.close()
        testSubject.close()
        assertThat(closed).isEqualTo(1)
        assertThat(runCatching { testSubject.own(AutoCloseable { closed++ }) }.isFailure).isTrue()
        assertThat(closed).isEqualTo(2)
    }

    @Test
    fun `operation cancellation closes provider and interrupts pending OAuth refresh`() {
        val grant = OAuthGrant(AccountIdFactory.create())
        val configuration = OAuthConfiguration(
            "synthetic",
            listOf("mail"),
            "https://login.example.invalid/auth",
            "https://login.example.invalid/token",
            "http://127.0.0.1:49152/callback",
        )
        val store = OAuthTokenStore(FakeCredentialStore())
        store.write(grant, configuration, OAuthTokens("expired", "refresh", 0))
        val entered = CountDownLatch(1)
        val coordinator = OAuthTokenCoordinator(
            store,
            OAuthTokenClient(
                OAuthTokenHttp { _, _ ->
                    entered.countDown()
                    awaitCancellation()
                },
            ) { 0 },
        ) { 0 }
        val resources = MailSessionResources(CancellableSocketFactory(JvmTrustedSocketFactory()))
        val provider = resources.own(CoordinatedOAuth2TokenProvider(grant, configuration, coordinator))
        val testSubject = MailOperation()
        testSubject.register(resources)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> { runCatching { provider.getToken(30000) }.isFailure }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            testSubject.close()
            assertThat(pending.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(store.read(grant, configuration)!!.refreshToken).isEqualTo("refresh")
            assertThat(runCatching { provider.getToken(1000) }.isFailure).isTrue()
        } finally {
            testSubject.close()
            worker.shutdownNow()
        }
    }
}
