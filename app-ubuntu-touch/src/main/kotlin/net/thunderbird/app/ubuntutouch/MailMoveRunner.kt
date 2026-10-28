package net.thunderbird.app.ubuntutouch

import kotlinx.coroutines.CancellationException
import net.thunderbird.feature.account.AccountId

internal class AccountHandoff(
    val account: AccountId,
    val authorize: (() -> Unit) -> Unit,
    val recordSynced: (String) -> Unit,
    val binding: String = "",
)

internal fun interface MailMoveTransport {
    fun move(action: MailMove, operation: MailOperation, beforeSubmit: () -> Unit)
}

internal class SessionMailMoveTransport(private val sessions: ForegroundMailSessionFactory) : MailMoveTransport {
    override fun move(action: MailMove, operation: MailOperation, beforeSubmit: () -> Unit) {
        sessions.open(action.account, operation).use { it.move(action, beforeSubmit) }
    }
}

/** Explicit foreground attempt only. Any exception after the durable claim is conservatively UNKNOWN. */
internal class MailMoveRunner(private val store: MailMoveStore, private val transport: MailMoveTransport) {
    fun run(
        expected: MailMove,
        operation: MailOperation,
        acknowledgeDuplicateRisk: Boolean = false,
    ): MailMove {
        store.checkCurrent(expected)
        require(
            expected.state == MailMoveState.QUEUED ||
                (expected.state == MailMoveState.UNKNOWN && acknowledgeDuplicateRisk),
        )
        var claim: MailMove? = null
        return try {
            operation.ensureActive()
            transport.move(expected, operation) {
                check(claim == null)
                operation.ensureActive()
                claim = store.claim(expected, acknowledgeDuplicateRisk)
            }
            store.finish(checkNotNull(claim) { "Transport returned without submission" }, confirmed = true)
        } catch (cancelled: CancellationException) {
            claim?.let { store.finish(it, confirmed = false) }
            throw cancelled
        } catch (_: Exception) {
            claim?.let { store.finish(it, confirmed = false) } ?: store.get(expected.account, expected.id)
        }
    }
}
