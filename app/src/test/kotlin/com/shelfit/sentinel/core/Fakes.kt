package com.shelfit.sentinel.core

import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.sensor.SensorStatus
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.smarthome.DeviceCommandReport
import com.shelfit.sentinel.core.smarthome.DeviceCommandResult
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeDevice
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
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

/**
 * A smart home under the test's control.
 *
 * The point of the seam: every failure the real integration can hit is a value here, so the
 * executor's behaviour is provable without an account, a network or a lamp.
 *
 * @param outcome the failure for a given device, or null for success.
 */
class FakeSmartHomeClient(
    initialState: SmartHomeState = SmartHomeState.Connected(
        structures = listOf(SmartHomeStructure(StructureId("home"), "Home")),
    ),
    override val available: Boolean = true,
    private val devices: List<SmartHomeDevice> = emptyList(),
    private val devicesFailure: SmartHomeFailure? = null,
    private val outcome: (DeviceId) -> SmartHomeFailure? = { null },
) : SmartHomeClient {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<SmartHomeState> = _state.asStateFlow()

    val commands = mutableListOf<Pair<SmartHomeCommand, List<DeviceId>>>()
    var connectCount: Int = 0
        private set

    fun emit(state: SmartHomeState) {
        _state.value = state
    }

    override suspend fun connect(): SmartHomeResult<Unit> {
        connectCount++
        return SmartHomeResult.Success(Unit)
    }

    override suspend fun disconnect() {
        _state.value = SmartHomeState.NotConnected
    }

    override suspend fun refresh() = Unit

    override suspend fun selectStructure(structureId: StructureId) {
        val connected = _state.value as? SmartHomeState.Connected ?: return
        _state.value = connected.copy(selected = structureId)
    }

    override suspend fun devices(
        structureId: StructureId,
    ): SmartHomeResult<List<SmartHomeDevice>> = devicesFailure
        ?.let { SmartHomeResult.Failure(it) }
        ?: SmartHomeResult.Success(devices)

    override suspend fun execute(
        command: SmartHomeCommand,
        targets: List<Pair<DeviceId, String>>,
    ): DeviceCommandReport {
        commands += command to targets.map { it.first }
        return DeviceCommandReport(
            targets.map { (id, name) -> DeviceCommandResult(id, name, outcome(id)) },
        )
    }
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
