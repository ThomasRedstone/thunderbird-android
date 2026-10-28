package net.thunderbird.feature.mail.message.list.internal

import net.thunderbird.core.common.inject.getList
import net.thunderbird.feature.mail.message.list.LocalDeleteOperationDecider
import net.thunderbird.feature.mail.message.list.domain.DomainContract
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.BuildSwipeActions
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.CreateArchiveFolder
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.GetSortCriteriaPerAccount
import net.thunderbird.feature.mail.message.list.internal.domain.usecase.SetArchiveFolder
import net.thunderbird.feature.mail.message.list.internal.ui.MessageListViewModel
import net.thunderbird.feature.mail.message.list.internal.ui.dialog.SetupArchiveFolderDialogFragment
import net.thunderbird.feature.mail.message.list.internal.ui.dialog.SetupArchiveFolderDialogViewModel
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import net.thunderbird.feature.mail.message.list.ui.dialog.SetupArchiveFolderDialogContract
import net.thunderbird.feature.mail.message.list.ui.dialog.SetupArchiveFolderDialogFragmentFactory
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val featureMessageListModule = module {
    includes(sharedMessageListModule)
    factory<DomainContract.UseCase.CreateArchiveFolder> {
        CreateArchiveFolder(
            accountManager = get(),
            backendStorageFactory = get(),
            specialFolderUpdaterFactory = get(),
            remoteFolderCreatorFactory = get(),
        )
    }
    factory<DomainContract.UseCase.SetArchiveFolder> {
        SetArchiveFolder(
            accountManager = get(),
            backendStorageFactory = get(),
            specialFolderUpdaterFactory = get(),
        )
    }
    factory<DomainContract.UseCase.BuildSwipeActions> {
        BuildSwipeActions(
            generalSettingsManager = get(),
            accountManager = get(),
        )
    }
    viewModel { parameters ->
        SetupArchiveFolderDialogViewModel(
            accountId = parameters.get(),
            logger = get(),
            getAccountFolders = get(),
            createArchiveFolder = get(),
            setArchiveFolder = get(),
            resourceManager = get(),
            generalSettingsManager = get(),
        ) as SetupArchiveFolderDialogContract.ViewModel
    }
    factory<SetupArchiveFolderDialogFragmentFactory> { SetupArchiveFolderDialogFragment.Factory }
    factory<DomainContract.UseCase.GetSortCriteriaPerAccount> {
        GetSortCriteriaPerAccount(
            accountManager = get(),
            getDefaultSortCriteria = get(),
        )
    }
    viewModel<MessageListContract.ViewModel> { parameters ->
        MessageListViewModel(
            logger = get(),
            messageListStateMachineFactory = get(),
            stateSideEffectHandlersFactories = getList { parameters },
            stringsResourceManager = get(),
        )
    }
    single<LocalDeleteOperationDecider> {
        DefaultLocalDeleteOperationDecider(
            accountManager = get(),
        )
    }
}
