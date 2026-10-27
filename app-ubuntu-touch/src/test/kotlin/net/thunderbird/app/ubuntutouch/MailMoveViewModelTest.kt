package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MailMoveViewModelTest {
    @Test
    fun `archive and trash require configured destinations and never submit while configuring`() = runTest {
        val operations = MoveUiFixture().apply {
            verified = true
            ready = true
        }
        val testSubject = MailMoveViewModel(operations, backgroundScope)
        testSubject.event(MailMoveEvent.Open(operations.key, MailFolderRole.TRASH))
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Target("Archive"))
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        assertThat(operations.queues).isEqualTo(0)
        testSubject.event(MailMoveEvent.ConfigureRole)
        testSubject.state.first { !it.busy }
        assertThat(operations.roles).isEqualTo(mapOf(MailFolderRole.TRASH to "Archive"))
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        assertThat(operations.queues).isEqualTo(1)
        assertThat(operations.submissions).isEqualTo(0)
        testSubject.event(MailMoveEvent.Open(operations.key, MailFolderRole.TRASH))
        assertThat(testSubject.state.first { !it.busy }.target).isEqualTo("Archive")
        testSubject.event(MailMoveEvent.ClearRole)
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        assertThat(operations.queues).isEqualTo(1)
        assertThat(operations.submissions).isEqualTo(0)
    }

    @Test
    fun `selection queueing and history never submit and unknown retry needs fresh confirmation`() = runTest {
        val operations = MoveUiFixture()
        val testSubject = MailMoveViewModel(operations, backgroundScope)
        testSubject.event(MailMoveEvent.Open(operations.key))
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Target("Archive"))
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        assertThat(operations.queues).isEqualTo(0)
        testSubject.event(MailMoveEvent.Verify)
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.SyncDestination)
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        assertThat(operations.queues).isEqualTo(1)
        assertThat(operations.submissions).isEqualTo(0)
        testSubject.event(MailMoveEvent.Submit)
        testSubject.state.first { !it.busy }
        assertThat(testSubject.state.value.selected?.state).isEqualTo(MailMoveState.UNKNOWN)
        testSubject.event(MailMoveEvent.ReviewRetry)
        testSubject.event(MailMoveEvent.Source())
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Back)
        testSubject.event(MailMoveEvent.ConfirmRetry)
        testSubject.state.first { !it.busy }
        assertThat(operations.submissions).isEqualTo(1)
        testSubject.event(MailMoveEvent.ReviewRetry)
        testSubject.event(MailMoveEvent.ConfirmRetry)
        testSubject.state.first { !it.busy }
        assertThat(operations.submissions).isEqualTo(2)
        testSubject.event(MailMoveEvent.Reconcile)
        testSubject.state.first { !it.busy }
        assertThat(operations.submissions).isEqualTo(2)
    }

    @Test
    fun `post submission exception reloads journal state without reflecting exception details`() = runTest {
        val operations = MoveUiFixture().apply {
            verified = true
            ready = true
            throwAfterSubmit = true
        }
        val testSubject = MailMoveViewModel(operations, backgroundScope)
        testSubject.event(MailMoveEvent.Open(operations.key))
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Target("Archive"))
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Submit)
        val recovered = testSubject.state.first { !it.busy }
        assertThat(recovered.selected?.state).isEqualTo(MailMoveState.UNKNOWN)
        assertThat(recovered.notice.orEmpty().contains("sensitive")).isFalse()
        assertThat(recovered.toString()).isEqualTo("MailMoveUiState(redacted)")
    }

    @Test
    fun `window close cancels network operation and waits for durable unknown review state`() = runTest {
        val operations = MoveUiFixture().apply {
            verified = true
            ready = true
            stall = true
        }
        val testSubject = MailMoveViewModel(operations, backgroundScope)
        testSubject.event(MailMoveEvent.Open(operations.key))
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Target("Archive"))
        testSubject.event(MailMoveEvent.Queue)
        testSubject.state.first { !it.busy }
        testSubject.event(MailMoveEvent.Submit)
        withContext(Dispatchers.IO) { check(operations.started.await(5, TimeUnit.SECONDS)) }
        assertThat(testSubject.flushForClose()).isTrue()
        assertThat(testSubject.state.value.selected?.state).isEqualTo(MailMoveState.UNKNOWN)
        assertThat(testSubject.state.value.busy).isFalse()
    }
}

private class MoveUiFixture : MailMoveOperations {
    val key = MailMessageKey(AccountIdFactory.create(), "INBOX", "1")
    var verified = false
    var ready = false
    val roles = mutableMapOf<MailFolderRole, String>()
    var queues = 0
    var submissions = 0
    var throwAfterSubmit = false
    var stall = false
    val started = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private var saved: MailMove? = null
    override fun overview(source: MailMessageKey?) = MoveOverview(
        source?.let { MoveSource(it, "Fixture", verified) },
        listOf(MoveDestination(MailFolder("Archive", "Archive"), ready)),
        listOfNotNull(saved?.let { MoveView(it, MailMovePayloadInfo(7, true, null), true) }),
        roles.toMap(),
    )
    override fun verify(source: MailMessageKey, operation: MailOperation) {
        verified = true
    }
    override fun syncDestination(
        source: MailMessageKey,
        target: String,
        operation: MailOperation,
    ) {
        ready = true
    }
    override fun configureRole(source: MailMessageKey, role: MailFolderRole, target: String?) {
        if (target == null) roles.remove(role) else roles[role] = target
    }
    override fun enqueue(source: MailMessageKey, target: String, role: MailFolderRole?): MailMove {
        if (role != null) check(roles[role] == target)
        queues++
        return MailMove(
            "fixture", key.account, key.folder, key.uid, target, 1, 2, MailMoveState.QUEUED, 0, false, "binding",
        )
            .also { saved = it }
    }
    override fun submit(
        move: MailMove,
        operation: MailOperation,
        acknowledgeDuplicateRisk: Boolean,
    ): MailMove {
        submissions++
        saved = move.copy(state = if (acknowledgeDuplicateRisk) MailMoveState.COMPLETE else MailMoveState.UNKNOWN)
        if (stall) {
            operation.register(AutoCloseable { stopped.countDown() })
            started.countDown()
            check(stopped.await(5, TimeUnit.SECONDS))
            operation.ensureActive()
        }
        check(!throwAfterSubmit) { "sensitive-server-detail" }
        return checkNotNull(saved)
    }
    override fun reconcile(move: MailMove, operation: MailOperation) = true
    override fun cancelQueued(move: MailMove) {
        saved = null
    }
    override fun retained(move: MailMove, offset: Long) = MailSourcePage(0, 7, true, "Fixture".toByteArray())
}
