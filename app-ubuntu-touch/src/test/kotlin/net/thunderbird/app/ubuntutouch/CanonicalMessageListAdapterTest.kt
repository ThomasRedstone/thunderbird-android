package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.list.domain.model.SortType
import net.thunderbird.feature.mail.message.list.ui.state.MessageItemUi
import org.junit.Test

class CanonicalMessageListAdapterTest {
    private val account = AccountIdFactory.create()
    private val folder = MailFolder("INBOX", "Inbox")

    @Test
    fun `cache projection preserves folder message flags sort and selected identity`() {
        val testSubject = LiveMailState(
            accounts = emptyList(),
            account = account,
            folder = folder,
            messages = listOf(
                MailSummary("one", "First", "Alice", unread = true, starred = true),
                MailSummary("two", "Second", "Bob", unread = false),
            ),
            sort = MailSort.SUBJECT,
            selection = MailSelection(true, setOf(MailMessageKey(account, folder.id, "two"))),
        )

        val result = checkNotNull(testSubject.canonicalMessageList())

        assertThat(result.metadata.folder?.id).isEqualTo("INBOX")
        assertThat(result.metadata.folder?.account?.id).isEqualTo(account)
        assertThat(result.metadata.sortCriteriaPerAccount[account]?.primary).isEqualTo(SortType.SubjectAsc)
        assertThat(result.messages.map { it.messageReference }).isEqualTo(listOf("one", "two"))
        assertThat(result.messages.map { it.state }).isEqualTo(
            listOf(MessageItemUi.State.Unread, MessageItemUi.State.Read),
        )
        assertThat(result.messages.map { it.starred }).isEqualTo(listOf(true, false))
        assertThat(result.messages.map { it.selected }).isEqualTo(listOf(false, true))
        assertThat(result.messages.map { it.excerpt }).isEqualTo(listOf("", ""))
    }

    @Test
    fun `projection is unavailable without a single active folder list`() {
        val list = LiveMailState(emptyList(), account = account, folder = folder)

        assertThat(list.copy(account = null).canonicalMessageList()).isNull()
        assertThat(list.copy(folder = null).canonicalMessageList()).isNull()
        assertThat(list.copy(searchScope = CachedSearchScope.ALL_ACCOUNTS).canonicalMessageList()).isNull()
        assertThat(
            list.copy(content = MailContent(MailSummary("one", "Subject", "Sender", false), "Body", true))
                .canonicalMessageList(),
        ).isNull()
    }

    @Test
    fun `legacy bridge emits only messages for the requested account and folder`() = runBlocking {
        val listed = LiveMailState(
            accounts = emptyList(),
            account = account,
            folder = folder,
            messages = listOf(MailSummary("one", "First", "Alice", unread = true)),
        )
        val source = MutableStateFlow(listed)
        val expected = checkNotNull(listed.canonicalMessageList())
        val testSubject = CachedMessageListBridge(source)

        val matching = testSubject.loadMessages(expected.preferences, expected.metadata)
        assertThat(matching.first().map { it.messageReference }).isEqualTo(listOf("one"))
        source.value = listed.copy(folder = MailFolder("Archive", "Archive"))
        assertThat(matching.first()).isEqualTo(emptyList())
        source.value = listed.copy(account = AccountIdFactory.create())
        assertThat(matching.first()).isEqualTo(emptyList())
    }
}
