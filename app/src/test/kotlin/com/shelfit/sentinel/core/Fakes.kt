package com.shelfit.sentinel.core

import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.sensor.SensorStatus
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.trigger.Trigger
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerDetector
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

data class FakeConfiguration(
    override val enabled: Boolean = true,
) : TriggerConfiguration

class FakeTrigger(
    override val id: TriggerId,
    override val defaultConfiguration: TriggerConfiguration = FakeConfiguration(),
    override val requiredSensors: Set<SensorKind> = emptySet(),
) : Trigger {
    override val displayName: String = id.value
    override val description: String = "fake"
    override val requiredPermissions: Set<String> = emptySet()
}

/** Emits [emissions] as soon as it is collected, then holds the flow open. */
class FakeTriggerDetector(
    override val trigger: Trigger,
    private val emissions: List<TriggerEvent> = emptyList(),
) : TriggerDetector {

    private val _state = MutableStateFlow<TriggerState>(TriggerState.Idle)
    override val state: StateFlow<TriggerState> = _state.asStateFlow()

    var collectCount: Int = 0
        private set

    var lastConfiguration: TriggerConfiguration? = null
        private set

    override fun events(configuration: TriggerConfiguration): Flow<TriggerEvent> =
        flow {
            collectCount++
            lastConfiguration = configuration
            _state.value = TriggerState.Active
            emissions.forEach { emit(it) }
            awaitCancellation()
        }.onCompletion { _state.value = TriggerState.Idle }
}

class RecordingActionExecutor(
    private val accepts: (Action) -> Boolean = { true },
    private val result: ActionResult = ActionResult.Success,
) : ActionExecutor {

    val executed = mutableListOf<Pair<Action, TriggerEvent>>()

    override fun canExecute(action: Action): Boolean = accepts(action)

    override suspend fun execute(action: Action, event: TriggerEvent): ActionResult {
        executed += action to event
        return result
    }
}

class ThrowingActionExecutor(private val error: Throwable) : ActionExecutor {
    override fun canExecute(action: Action): Boolean = true
    override suspend fun execute(action: Action, event: TriggerEvent): ActionResult = throw error
}

class MutableClock(var now: Long = 0L) : MonotonicClock {
    override fun elapsedMillis(): Long = now
}

class FakeSensorStatusProvider(
    private val availability: Map<SensorKind, SensorAvailability>,
) : SensorStatusProvider {
    override fun statusOf(kind: SensorKind): SensorStatus =
        SensorStatus(kind, availability[kind] ?: SensorAvailability.UNSUPPORTED)
}
