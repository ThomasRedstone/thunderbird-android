package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import java.io.IOException
import java.net.URISyntaxException
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.oauth.OAuthConfiguration

/** For the engine's blocking authentication calls. Share the coordinator across all protocol/store providers. */
internal class CoordinatedOAuth2TokenProvider(
    private val grant: OAuthGrant,
    configuration: OAuthConfiguration,
    private val coordinator: OAuthTokenCoordinator,
    verifiedUsernames: Set<String> = emptySet(),
    requiredScopes: Set<String> = emptySet(),
    private val identity: OAuthIdentityRequirement? = null,
) : OAuth2TokenProvider, AutoCloseable {
    private val configuration = configuration.copy(scopes = configuration.scopes.toList())
    private val requiredScopes = requiredScopes.toSet()
    private val alternatives = verifiedUsernames.toSet()
    private val lifecycle = SupervisorJob()
    private val lock = ReentrantLock()

    // ImapStore shares its provider across connections. Auth calls stay on their calling thread.
    private val issuedTokens = WeakHashMap<Thread, String>()

    init {
        require(alternatives.size <= MAX_ALTERNATIVES && alternatives.all(::validUsername)) {
            "Invalid verified OAuth usernames"
        }
    }

    /** Caller supplies verified provider identities. This bridge never decodes or trusts an ID token. */
    override val usernames: Set<String> get() = alternatives.toSet()

    override fun getToken(timeoutMillis: Long): String = blocking(timeoutMillis) {
        coordinator.token(grant, configuration, requiredScopes, identity).also {
            issuedTokens[Thread.currentThread()] =
                it
        }
    }

    override fun invalidateToken() {
        blocking(MAX_TIMEOUT_MILLIS) {
            issuedTokens[Thread.currentThread()]?.let { rejected ->
                coordinator.invalidate(grant, configuration, rejected)
                issuedTokens.remove(Thread.currentThread())
            }
        }
    }

    private fun <T> blocking(timeoutMillis: Long, operation: suspend () -> T): T = checked {
        if (SwingUtilities.isEventDispatchThread()) authenticationFailure("OAuth must run off the UI thread")
        if (timeoutMillis !in 1..MAX_TIMEOUT_MILLIS) authenticationFailure("Invalid OAuth request timeout")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        acquire(deadline)
        try {
            runBlocking(lifecycle) { withTimeout(remainingMillis(deadline)) { operation() } }
        } finally {
            if (!lifecycle.isActive) issuedTokens.clear()
            lock.unlock()
        }
    }

    private fun acquire(deadline: Long) {
        while (true) {
            lifecycle.ensureActive()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException()
            if (lock.tryLock(minOf(remaining, LOCK_POLL_NANOS), TimeUnit.NANOSECONDS)) return
        }
    }

    override fun close() {
        lifecycle.cancel()
        if (lock.tryLock()) {
            try {
                issuedTokens.clear()
            } finally {
                lock.unlock()
            }
        }
    }

    override fun toString(): String = "CoordinatedOAuth2TokenProvider(redacted)"
}

private fun remainingMillis(deadline: Long): Long {
    val nanos = deadline - System.nanoTime()
    if (nanos <= 0) throw TimeoutException()
    return (TimeUnit.NANOSECONDS.toMillis(nanos) + 1).coerceAtMost(MAX_TIMEOUT_MILLIS)
}

private const val MAX_TIMEOUT_MILLIS = 30_000L
private const val LOCK_POLL_NANOS = 100_000_000L
private const val MAX_ALTERNATIVES = 16
private const val MAX_USERNAME_LENGTH = 320

private fun authenticationFailure(message: String): Nothing = throw AuthenticationFailedException(message)

private fun <T> checked(operation: () -> T): T {
    try {
        return operation()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        authenticationFailure("OAuth request interrupted")
    } catch (_: CancellationException) {
        authenticationFailure("OAuth request cancelled or timed out")
    } catch (_: TimeoutException) {
        authenticationFailure("OAuth request cancelled or timed out")
    } catch (failure: OAuthTokenException) {
        authenticationFailure("OAuth token request: ${failure.reason.name}")
    } catch (_: OAuthTokenStorageException) {
        authenticationFailure("Stored OAuth authorization requires sign-in")
    } catch (failure: SecretStoreException) {
        authenticationFailure("Protected OAuth storage: ${failure.reason.name}")
    } catch (_: IOException) {
        authenticationFailure("OAuth service unavailable")
    } catch (_: URISyntaxException) {
        authenticationFailure("Invalid OAuth configuration")
    } catch (_: IllegalArgumentException) {
        authenticationFailure("Invalid OAuth configuration")
    }
}

private fun validUsername(name: String): Boolean = name.length <= MAX_USERNAME_LENGTH && '@' in name &&
    name.none { it.isWhitespace() || it.isISOControl() }
