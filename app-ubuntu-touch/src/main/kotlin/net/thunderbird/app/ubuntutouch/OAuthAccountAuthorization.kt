package net.thunderbird.app.ubuntutouch

import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.feature.account.AccountId

/** Browser/code exchange orchestration; registration and provider identity validation remain composition concerns. */
internal class OAuthAccountAuthorization(
    private val accounts: UtAccountManager,
    private val providers: OAuthProviderRegistry,
    private val browser: OAuthBrowser,
    private val tokens: OAuthTokenClient,
    private val receiver: ((URI) -> OAuthConfiguration) -> OAuthLoopbackReceiver = { OAuthLoopbackReceiver.open(it) },
) {
    fun prepare(candidate: UtMailAccount, expected: UtMailAccount? = null): OAuthAccountAuthorizationAttempt {
        val configuration = providers.configurationFor(candidate)
        require(expected == null || (expected.id == candidate.id && expected.credentialKey != candidate.credentialKey))
        return OAuthAccountAuthorizationAttempt(accounts, browser, tokens, receiver, configuration, candidate, expected)
    }
}

internal data class OAuthAccountAuthorizationResult(val accountId: AccountId, val cleanupPending: Boolean)
internal class OAuthAuthorizationDeniedException : IOException("Authorization was declined")

/** One-use owner. UI cancellation must call cancel() before cancelling its coroutine or leaving setup. */
@Suppress("LongParameterList") // Immutable dependencies and account snapshot belong to this single attempt only.
internal class OAuthAccountAuthorizationAttempt(
    private val accounts: UtAccountManager,
    private val browser: OAuthBrowser,
    private val tokens: OAuthTokenClient,
    private val receiver: ((URI) -> OAuthConfiguration) -> OAuthLoopbackReceiver,
    private val configuration: OAuthConfiguration,
    private val candidate: UtMailAccount,
    private val expected: UtMailAccount?,
) : AutoCloseable {
    private val phase = AtomicReference(Phase.READY)
    private val worker = AtomicReference<Job?>()
    private val activeReceiver = AtomicReference<OAuthLoopbackReceiver?>()
    val committing: Boolean get() = phase.get() == Phase.COMMITTING

    suspend fun authorize(): OAuthAccountAuthorizationResult {
        check(phase.compareAndSet(Phase.READY, Phase.RUNNING)) { "Authorization attempt already used" }
        return try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                worker.set(context.job)
                if (phase.get() == Phase.CANCELLED) context.job.cancel()
                context.ensureActive()
                check(accounts.findById(candidate.id) == expected) { "Account changed during authorization" }
                var allocated: OAuthConfiguration? = null
                receiver { redirect ->
                    configuration.copy(redirectUri = redirect.toASCIIString()).also { allocated = it }
                }.use { callback ->
                    activeReceiver.set(callback)
                    if (phase.get() == Phase.CANCELLED) callback.close()
                    context.ensureActive()
                    browser.open(callback.authorizationUri)
                    context.ensureActive()
                    val authorized = callback.await() as? OAuthCallback.Authorized
                        ?: throw OAuthAuthorizationDeniedException()
                    val replacement = tokens.exchange(checkNotNull(allocated), authorized)
                    context.ensureActive()
                    if (!phase.compareAndSet(Phase.RUNNING, Phase.COMMITTING)) {
                        throw CancellationException("Authorization cancelled")
                    }
                    // A protected write may block. Finish its journaled commit rather than report a false cancellation.
                    withContext(NonCancellable) {
                        val cleaned = if (expected == null) {
                            accounts.createWithOAuth(candidate, replacement)
                            accounts.cleanupUnusedCredentials()
                        } else {
                            accounts.replaceWithOAuth(expected, candidate, replacement)
                        }
                        OAuthAccountAuthorizationResult(candidate.id, cleanupPending = !cleaned)
                    }
                }
            }
        } finally {
            activeReceiver.set(null)
            worker.set(null)
            phase.set(Phase.FINISHED)
        }
    }

    /** False means the protected commit has started or the attempt has already finished. Never blocks on keyring IO. */
    fun cancel(): Boolean {
        while (true) {
            val current = phase.get()
            if (current != Phase.READY && current != Phase.RUNNING) return false
            if (phase.compareAndSet(current, Phase.CANCELLED)) {
                worker.get()?.cancel()
                activeReceiver.get()?.close()
                return true
            }
        }
    }

    override fun close() {
        cancel()
    }

    override fun toString(): String = "OAuthAccountAuthorizationAttempt(redacted)"
    private enum class Phase { READY, RUNNING, COMMITTING, CANCELLED, FINISHED }
}
