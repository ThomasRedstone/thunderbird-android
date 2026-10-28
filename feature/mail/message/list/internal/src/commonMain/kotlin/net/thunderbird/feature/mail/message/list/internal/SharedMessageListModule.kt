package net.thunderbird.feature.mail.message.list.internal

import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider
import net.thunderbird.feature.mail.message.list.domain.DomainContract
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.GetAccountFolders
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.GetMessageListPreferences
import net.thunderbird.feature.mail.message.list.internal.ui.state.machine.MessageListStateMachine
import net.thunderbird.feature.mail.message.list.internal.ui.state.sideeffect.inject.messageListSideEffectsModule
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
    single<LocalMessageUidPrefixProvider> { DefaultLocalMessageUidPrefixProvider() }
}
