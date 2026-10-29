package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.thunderbird.app.composition.appCompositionModule
import net.thunderbird.components.core.logging.DefaultLogger
import net.thunderbird.components.core.logging.LogEvent
import net.thunderbird.components.core.logging.LogLevel
import net.thunderbird.components.core.logging.LogSink
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.event.MessageItemEvent
import net.thunderbird.feature.mail.message.list.ui.state.MessageListState
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalMessageListControllerTest {
    @Test
    fun `shared reducer follows cached folder switches and opens the current message`() = runTest {
        val factory = koinApplication {
            modules(
                appCompositionModule,
                module {
                    single<Logger> {
                        DefaultLogger(object : LogSink {
                            override val level = LogLevel.INFO
                            override fun log(event: LogEvent) = error("Unexpected reducer log")
                        })
                    }
                },
            )
        }.koin.get<MessageListContract.StateMachineFactory>()
        val account = AccountIdFactory.create()
        val inbox = MailFolder("INBOX", "Inbox")
        val archive = MailFolder("Archive", "Archive")
        val mailbox = MutableStateFlow(
            LiveMailState(
                accounts = emptyList(),
                account = account,
                folder = inbox,
                messages = listOf(MailSummary("inbox-1", "First", "Sender", unread = true)),
            ),
        )
        val events = mutableListOf<LiveMailEvent>()
        val controllerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val testSubject = CanonicalMessageListController(mailbox, events::add, factory, controllerScope)

        advanceUntilIdle()
        val initial = testSubject.state.value as MessageListState.LoadedMessages
        assertThat(initial.metadata.folder?.id).isEqualTo("INBOX")
        assertThat(initial.messages.map { it.id }).isEqualTo(listOf("inbox-1"))

        mailbox.value = mailbox.value.copy(
            folder = archive,
            messages = listOf(MailSummary("archive-1", "Second", "Sender", unread = false)),
        )
        advanceUntilIdle()
        val switched = testSubject.state.value as MessageListState.LoadedMessages
        assertThat(switched.metadata.folder?.id).isEqualTo("Archive")
        assertThat(switched.messages.map { it.id }).isEqualTo(listOf("archive-1"))

        testSubject.event(MessageItemEvent.OnMessageClick(switched.messages.single()))
        advanceUntilIdle()
        assertThat(events).isEqualTo(listOf(LiveMailEvent.OpenMessage("archive-1")))
        assertThat(testSubject.state.value).isInstanceOf<MessageListState.LoadedMessages>()
        testSubject.close()
    }
}
