package net.thunderbird.app.ubuntutouch

import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import net.thunderbird.feature.account.AccountId

internal enum class ExportBrokerState { CREATED, INITIATED, IN_PROGRESS, CHARGED, COLLECTED, ABORTED, FINALIZED }

internal data class ExportDestination(val id: String, val name: String) {
    override fun toString(): String = "ExportDestination(redacted)"
}

/** The adapter must pin the unique service owner, validate paths and authenticate signal senders. IO callers only. */
internal interface ContentExportBroker {
    fun destinations(): List<ExportDestination>
    fun create(destination: ExportDestination): ExportTransferReference
    fun start(reference: ExportTransferReference)
    fun charge(reference: ExportTransferReference, file: Path)
    fun state(reference: ExportTransferReference): ExportBrokerState
    fun abort(reference: ExportTransferReference)
    fun forget(reference: ExportTransferReference) = Unit
}

internal data class ExportReconcileResult(val record: ExportLeaseRecord?, val state: ExportBrokerState? = null)

/** One owner serializes prepare/send/recovery. Closing the window must not discard attached or uncertain files. */
internal class ContentExportController(
    private val exporter: CachedMessageExport,
    private val journal: ContentExportJournal,
    private val broker: ContentExportBroker,
    private val accountExists: (AccountId) -> Boolean,
) {
    private val active = mutableMapOf<UUID, () -> Boolean>()

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // Keep durable cleanup metadata on preparation failure.
    fun prepare(
        account: AccountId,
        folder: String,
        uid: String,
        authorized: () -> Boolean,
        checkActive: () -> Unit = {},
    ): ExportLeaseRecord {
        check(accountExists(account) && authorized())
        check(journal.records().isEmpty()) { "Review pending exports before starting another" }
        var record: ExportLeaseRecord? = null
        var lease: PreparedAttachment? = null
        try {
            lease = exporter.prepare(account, folder, uid, reserved = { file ->
                record = journal.reserve(file, account)
                active[checkNotNull(record).id] = authorized
            }, checkActive = checkActive)
            check(accountExists(account) && authorized())
            return journal.ready(checkNotNull(record))
        } catch (failure: Throwable) {
            try {
                lease?.close()
                record?.let { journal.currentRecord(it.id)?.let(::release) }
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    @Synchronized
    fun destinations(): List<ExportDestination> = broker.destinations()

    @Synchronized
    fun send(record: ExportLeaseRecord, destination: ExportDestination): ExportReconcileResult {
        check(
            record.id in active && journal.currentRecord(record.id) == record && record.phase == ExportLeasePhase.READY,
        )
        if (!allowed(record)) return cancel(record.id)
        return preservingUncertainty(record.id) {
            require(destination in broker.destinations())
            val reference = broker.create(destination)
            val attached = journal.attach(record, reference)
            broker.start(reference)
            val state = broker.state(reference)
            if (!allowed(record)) {
                cancel(record.id)
            } else if (journal.currentRecord(record.id) == attached && state == ExportBrokerState.IN_PROGRESS) {
                // Persist before sending. No recovery path retries an ambiguous Charge.
                journal.charging(attached)
                if (allowed(record)) {
                    broker.charge(reference, journal.file(attached))
                    poll(record.id)
                } else {
                    cancel(record.id)
                }
            } else {
                observed(record.id, reference, state)
            }
        }
    }

    @Synchronized
    fun poll(id: UUID): ExportReconcileResult {
        val record = journal.currentRecord(id)
        val reference = record?.transfer
        return if (reference == null) {
            ExportReconcileResult(record)
        } else {
            preservingUncertainty(id) { observed(id, reference, broker.state(reference)) }
        }
    }

    @Synchronized
    fun cancel(id: UUID): ExportReconcileResult {
        val record = journal.currentRecord(id)
        val reference = record?.transfer
        return when {
            record == null -> ExportReconcileResult(null)

            reference == null -> {
                release(record)
                ExportReconcileResult(null)
            }

            else -> preservingUncertainty(id) {
                broker.abort(reference)
                poll(id)
            }
        }
    }

    /** Explicit user review only. Removing this private copy does not recall any destination copy. */
    @Synchronized
    fun discardLocalCopy(expected: ExportLeaseRecord): ExportReconcileResult {
        require(expected.phase.hasTransfer)
        check(journal.currentRecord(expected.id) == expected) { "Export changed; review again" }
        release(expected)
        return ExportReconcileResult(null)
    }

    @Synchronized
    fun recover(): List<ExportReconcileResult> = journal.records().map { record ->
        if (!accountExists(record.account) || (record.id in active && !allowed(record))) {
            cancel(record.id)
        } else if (!record.phase.hasTransfer && record.id !in active) {
            release(record)
            ExportReconcileResult(null)
        } else {
            poll(record.id)
        }
    }

    /** Duplicate/late notifications cannot target a different owner/path or resurrect an already released export. */
    @Synchronized
    fun observed(id: UUID, reference: ExportTransferReference, state: ExportBrokerState): ExportReconcileResult {
        val record = journal.currentRecord(id)
        return when {
            record == null -> ExportReconcileResult(null)

            record.transfer != reference -> ExportReconcileResult(record)

            state == ExportBrokerState.ABORTED || state == ExportBrokerState.FINALIZED -> {
                release(record)
                ExportReconcileResult(null, state)
            }

            else -> ExportReconcileResult(record, state)
        }
    }

    private fun allowed(record: ExportLeaseRecord): Boolean =
        accountExists(record.account) && active[record.id]?.invoke() == true

    private fun release(record: ExportLeaseRecord) {
        journal.release(record)
        active -= record.id
        record.transfer?.let { runCatching { broker.forget(it) } }
    }

    private fun preservingUncertainty(id: UUID, work: () -> ExportReconcileResult): ExportReconcileResult = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ExportReconcileResult(journal.currentRecord(id))
    }
}

private fun ContentExportJournal.currentRecord(id: UUID): ExportLeaseRecord? = records().singleOrNull { it.id == id }
