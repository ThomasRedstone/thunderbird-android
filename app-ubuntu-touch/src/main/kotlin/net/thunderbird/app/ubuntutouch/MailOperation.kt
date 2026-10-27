package net.thunderbird.app.ubuntutouch

import java.io.InterruptedIOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Cancellation request is immediate; blocking resource teardown runs on IO, including during model disposal. */
internal class MailOperation : AutoCloseable {
    private val monitor = Any()
    private val owners = mutableSetOf<AutoCloseable>()

    @Volatile var cancellationRequested = false
        private set

    fun ensureActive() {
        if (cancellationRequested) throw MailOperationCancelledException()
    }

    fun register(owner: AutoCloseable) {
        synchronized(monitor) {
            if (!cancellationRequested) {
                owners += owner
                return
            }
        }
        owner.close()
        throw MailOperationCancelledException()
    }

    fun release(owner: AutoCloseable) {
        synchronized(monitor) { owners -= owner }
    }

    fun cancelIn(scope: CoroutineScope) {
        synchronized(monitor) { cancellationRequested = true }
        // Cleanup must outlive scope.cancel(); no UI or model state is captured by this short-lived job.
        scope.launch(Dispatchers.IO + NonCancellable) { close() }
    }

    override fun close() {
        val pending = synchronized(monitor) {
            cancellationRequested = true
            owners.toList().also { owners.clear() }
        }
        closeMailResources(pending)
    }
}

/** Thrown only by preparation checks, before a transport can submit message data. */
internal class MailOperationCancelledException : InterruptedIOException("Mail operation cancelled before submission")

/** Close every owned resource even when one teardown fails. */
@Suppress("TooGenericExceptionCaught") // Teardown must release every owned resource before propagating a failure.
internal fun closeMailResources(resources: List<AutoCloseable>) {
    var failure: Throwable? = null
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (problem: Exception) {
            if (failure == null) failure = problem else failure!!.addSuppressed(problem)
        }
    }
    failure?.let { throw it }
}
