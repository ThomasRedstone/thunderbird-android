package net.thunderbird.core.ui.compose.common.mvi

import net.thunderbird.components.core.logging.Logger
import net.thunderbird.core.common.state.sideeffect.StateSideEffectHandler
import net.thunderbird.core.ui.contract.mvi.BaseStateMachineViewModel as SharedStateMachineViewModel

/** Compatibility entry point for existing Android callers; behaviour is owned by the shared implementation. */
abstract class BaseStateMachineViewModel<TState : Any, TEvent : Any, TUiSideEffect : Any>(
    logger: Logger,
    sideEffectHandlersFactories: List<StateSideEffectHandler.Factory<TState, TEvent, TUiSideEffect>> = emptyList(),
) : SharedStateMachineViewModel<TState, TEvent, TUiSideEffect>(logger, sideEffectHandlersFactories)
