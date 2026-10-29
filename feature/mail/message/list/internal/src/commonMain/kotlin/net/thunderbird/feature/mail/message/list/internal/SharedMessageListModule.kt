package net.thunderbird.feature.mail.message.list.internal

import kotlin.time.Clock
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider
import net.thunderbird.feature.mail.message.list.domain.DomainContract
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.GetAccountFolders
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.GetMessageListPreferences
import net.thunderbird.feature.mail.message.list.internal.ui.MessageListScreenRenderer
import net.thunderbird.feature.mail.message.list.internal.ui.state.machine.MessageListStateMachine
import net.thunderbird.feature.mail.message.list.internal.ui.state.sideeffect.inject.messageListSideEffectsModule
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import org.koin.dsl.module

/** Canonical message-list state and data orchestration; platform hosts supply the external contracts. */
val sharedMessageListModule = module {
    includes(messageListSideEffectsModule)
    factory<DomainContract.UseCase.GetAccountFolders> { GetAccountFolders(remoteFolderQueryRepository = get()) }
    factory<DomainContract.UseCase.GetMessageListPreferences> {
        GetMessageListPreferences(displayPreferenceManager = get(), interactionPreferenceManager = get())
    }
    factory {
        MessageListStateMachine.Factory(logger = get(), clock = get(), debuggingSettingsPreferenceManager = get())
    }
    factory<MessageListContract.StateMachineFactory> {
        MessageListStateMachine.Factory(logger = get(), clock = Clock.System, debuggingSettingsPreferenceManager = null)
    }
    factory<MessageListContract.MessageListScreenRenderer> { MessageListScreenRenderer() }
    single<LocalMessageUidPrefixProvider> { DefaultLocalMessageUidPrefixProvider() }
}
