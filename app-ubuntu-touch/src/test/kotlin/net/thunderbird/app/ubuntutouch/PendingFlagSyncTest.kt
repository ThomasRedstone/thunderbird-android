package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.io.IOException
import kotlinx.coroutines.CancellationException
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class PendingFlagSyncTest {
    private val first = AccountIdFactory.create()
    private val second = AccountIdFactory.create()

    @Test
    fun `one failed folder does not block other folders or accounts and duplicate targets run once`() {
        val calls = mutableListOf<Pair<Any, String>>()
        val testSubject = syncPendingFlagFolders(
            listOf(first, second, first),
            MailOperation(),
            { listOf("INBOX", "Archive", "INBOX") },
        ) { account, folder, _ ->
            calls += account to folder
            if (account == first && folder == "INBOX") throw IOException("Do not expose server input")
            !(account == second && folder == "Archive")
        }
        assertThat(testSubject).isEqualTo(FlagSyncResult(2, 2))
        assertThat(
            calls,
        ).isEqualTo(listOf(first to "INBOX", first to "Archive", second to "INBOX", second to "Archive"))
        assertThat(testSubject.notice())
            .isEqualTo("Synced 2 changed folders; 2 still need attention. Unsynced changes remain queued.")
    }

    @Test
    fun `cancellation stops before another folder and coroutine cancellation is not converted to failure`() {
        val operation = MailOperation()
        var calls = 0
        val testSubject = runCatching {
            syncPendingFlagFolders(listOf(first, second), operation, { listOf("INBOX", "Archive") }) { _, _, active ->
                calls++
                active.close()
                throw IOException("Transport closed")
            }
        }
        assertThat(testSubject.exceptionOrNull()).isNotNull().isInstanceOf<MailOperationCancelledException>()
        assertThat(calls).isEqualTo(1)
        val coroutine = runCatching {
            syncPendingFlagFolders(listOf(first), MailOperation(), { listOf("INBOX") }) { _, _, _ ->
                throw CancellationException("Cancelled")
            }
        }
        assertThat(coroutine.exceptionOrNull()).isNotNull().isInstanceOf<CancellationException>()
    }

    @Test
    fun `empty queue does no network work and pre cancellation avoids even reading targets`() {
        val testSubject = syncPendingFlagFolders(listOf(first), MailOperation(), { emptyList() }) { _, _, _ ->
            error("No network expected")
        }
        assertThat(testSubject.notice()).isEqualTo("No queued flag changes to sync.")
        val operation = MailOperation().apply { close() }
        var reads = 0
        val cancelled = runCatching {
            syncPendingFlagFolders(listOf(first), operation, {
                reads++
                listOf("INBOX")
            }) { _, _, _ -> true }
        }
        assertThat(cancelled.exceptionOrNull()).isNotNull().isInstanceOf<MailOperationCancelledException>()
        assertThat(reads).isEqualTo(0)
    }
}
