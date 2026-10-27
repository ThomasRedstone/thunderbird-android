package net.thunderbird.app.ubuntutouch

import java.io.InterruptedIOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Cancellation request is immediate; blocking resource teardown runs on IO, including during model disposal. */
internal class MailOperation : AutoCloseable {
    private val monitor = Any()
    private val owners = mutableSetOf<CancellableSocketFactory>()

    @Volatile var cancellationRequested = false
        private set

    fun ensureActive() {
        if (cancellationRequested) throw MailOperationCancelledException()
    }

    fun register(owner: CancellableSocketFactory) {
        synchronized(monitor) {
            if (!cancellationRequested) {
                owners += owner
                return
            }
        }
        owner.close()
        throw MailOperationCancelledException()
    }

    fun release(owner: CancellableSocketFactory) {
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
        pending.forEach { it.close() }
    }
}

/** Thrown only by preparation checks, before a transport can submit message data. */
internal class MailOperationCancelledException : InterruptedIOException("Mail operation cancelled before submission")
