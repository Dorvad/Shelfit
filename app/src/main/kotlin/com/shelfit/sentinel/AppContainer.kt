package com.shelfit.sentinel

import android.content.Context
import com.shelfit.sentinel.core.WallClock
import com.shelfit.sentinel.core.action.ActionCatalogue
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.diagnostics.EventLog
import com.shelfit.sentinel.core.rule.AutomationCoordinator
import com.shelfit.sentinel.core.sensormode.SensorModeError
import com.shelfit.sentinel.core.sensormode.SensorModeRuntime
import com.shelfit.sentinel.core.action.SmartHomeActionExecutor
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeDirectory
import com.shelfit.sentinel.core.smarthome.SmartHomeProvider
import com.shelfit.sentinel.core.trigger.TriggerEngine
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerRegistry
import com.shelfit.sentinel.data.RuleRepository
import com.shelfit.sentinel.data.SensorHealthRepository
import com.shelfit.sentinel.data.SensorModeStore
import com.shelfit.sentinel.data.SettingsRepository
import com.shelfit.sentinel.data.triggerConfigurations
import com.shelfit.sentinel.platform.AndroidSensorStatusProvider
import com.shelfit.sentinel.platform.LogActionExecutor
import com.shelfit.sentinel.platform.NotificationActionExecutor
import com.shelfit.sentinel.platform.SensorEnvironment
import com.shelfit.sentinel.platform.SystemMonotonicClock
import com.shelfit.sentinel.platform.SystemWallClock
import com.shelfit.sentinel.platform.VibrationActionExecutor
import com.shelfit.sentinel.platform.audio.AudioRecordInput
import com.shelfit.sentinel.platform.smarthome.GoogleHomeClient
import com.shelfit.sentinel.platform.smarthome.SelectableSmartHomeClient
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaCloudClient
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaCredentials
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaLanDiscovery
import com.shelfit.sentinel.service.SensorModeController
import com.shelfit.sentinel.trigger.audio.ClapCalibrator
import com.shelfit.sentinel.trigger.audio.DoubleClapConfiguration
import com.shelfit.sentinel.trigger.audio.DoubleClapDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency container, held by [SentinelApplication] for the process
 * lifetime.
 *
 * Hand-wired on purpose: the graph is a dozen objects and a DI framework would add
 * build time and indirection without removing any real work.
 *
 * **This is the extension point.** A new trigger type is registered by adding its
 * detector to [triggerRegistry]; a new capability is registered by adding its
 * executor to the action dispatcher. Nothing else in the app needs to change.
 */
class AppContainer(context: Context) {

    /**
     * Outlives any screen — detection must survive the UI going away. `Default`
     * rather than `Main`: sensor processing is CPU work and must stay off the UI
     * thread.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sensorStatusProvider: SensorStatusProvider = AndroidSensorStatusProvider(context)

    val settingsRepository = SettingsRepository(context)

    /**
     * The pretend home, exposed as its own type so the developer screen can stage faults
     * on it.
     *
     * A concession like `DoubleClapDetector.diagnostics`: a developer tool needs more than
     * the abstraction offers, and widening [SmartHomeClient] with fault injection to serve
     * one screen would put test machinery in the contract a real provider has to implement.
     */
    val simulatedSmartHome = SimulatedSmartHomeClient()

    /**
     * The one place smart-home providers are registered.
     *
     * [TuyaCloudClient] is the working one. [GoogleHomeClient] reports "not configured" until
     * the Home APIs SDK is in the build, and the simulator is a developer aid. Everything
     * above this holds the interface, which is why adding Tuya changed no audio code, no rule
     * code and no screen outside the smart-home settings.
     */
    val smartHomeClient: SmartHomeClient = SelectableSmartHomeClient(
        clients = mapOf(
            SmartHomeProvider.TUYA to TuyaCloudClient { tuyaCredentials },
            SmartHomeProvider.GOOGLE to GoogleHomeClient(),
            SmartHomeProvider.SIMULATED to simulatedSmartHome,
            // SmartHomeProvider.NONE is deliberately absent — the router serves it with a
            // client that reports "not configured", so no caller needs a null check.
        ),
        chosen = settingsRepository.settings.map { it.smartHomeProvider },
        scope = applicationScope,
    )

    /**
     * The Tuya keys, kept in memory so signing a request never has to suspend.
     *
     * A request is signed inside `execute`, which runs when a clap fires. Reading DataStore
     * at that moment would put a disk read on the path between the gesture and the light.
     */
    @Volatile
    private var tuyaCredentials = TuyaCredentials()

    /**
     * Finds Tuya devices announcing themselves on the LAN.
     *
     * Independent of the cloud client on purpose: it works before any account exists, and its
     * job right now is to tell the user which protocol version their devices speak — the fact
     * that decides how local control gets implemented. See `docs/tuya-lan.md`.
     */
    val tuyaLanDiscovery = TuyaLanDiscovery()

    /** The chosen home's devices, shared by the connect screen and the rule editor. */
    val smartHomeDirectory = SmartHomeDirectory(smartHomeClient, applicationScope)

    /** The actions the rule editor may offer. Paired with the executors below. */
    val actionCatalogue = ActionCatalogue.WithSmartHome

    val ruleRepository = RuleRepository(context, actionCatalogue)

    /** Operational state for Sensor Mode: the desired mode, timestamps, last error. */
    val sensorModeStore = SensorModeStore(context)

    /** Whether the foreground service exists in this process. */
    val sensorModeRuntime = SensorModeRuntime()

    val sensorEnvironment = SensorEnvironment(context)

    /**
     * Metadata-only history of detector decisions, kept in memory for the lifetime of
     * the process. Never written to disk — see [EventLog].
     */
    val wallClock: WallClock = SystemWallClock

    val eventLog = EventLog(wallClock)

    private val audioInput = AudioRecordInput(context)

    /**
     * Measures the room and the user's claps. Shares [audioInput] with the detector,
     * so callers must stop detection before calibrating — the microphone serves one
     * client at a time.
     */
    val clapCalibrator = ClapCalibrator(audioInput)

    /**
     * Exposed as its concrete type so the detector test screen can read
     * [DoubleClapDetector.diagnostics]. The engine still only sees a
     * `TriggerDetector`.
     */
    val doubleClapDetector = DoubleClapDetector(
        audioInput = audioInput,
        sensorStatus = sensorStatusProvider,
        clock = SystemMonotonicClock,
        eventLog = eventLog,
    )

    val triggerRegistry = TriggerRegistry(
        detectors = listOf(
            doubleClapDetector,
            // Register future detectors here — camera gestures, ambient light,
            // accelerometer. Each one only implements TriggerDetector.
        ),
    )

    val triggerEngine = TriggerEngine(
        registry = triggerRegistry,
        scope = applicationScope,
    )

    private val actionDispatcher = ActionDispatcher(
        executors = listOf(
            VibrationActionExecutor(context) {
                settingsRepository.settings.first().hapticFeedbackEnabled
            },
            NotificationActionExecutor(context, triggerRegistry),
            LogActionExecutor(),
            SmartHomeActionExecutor(smartHomeClient),
            // Register future executors here — webhooks, HTTP. Anything added to
            // actionCatalogue needs one, and the init block below enforces that.
        ),
    )

    val automationCoordinator = AutomationCoordinator(
        events = triggerEngine.events,
        rules = ruleRepository.rules,
        dispatcher = actionDispatcher,
        clock = SystemMonotonicClock,
        scope = applicationScope,
    )

    /** The single entry point for turning listening on and off. */
    val sensorModeController = SensorModeController(
        context = context,
        store = sensorModeStore,
        clock = wallClock,
    )

    val sensorHealthRepository = SensorHealthRepository(
        store = sensorModeStore,
        environment = sensorEnvironment,
        runtime = sensorModeRuntime,
        engine = triggerEngine,
    )

    init {
        // An action offered in the editor with nothing able to perform it would be a rule
        // that silently does nothing. Fail here, on a developer's machine.
        val unsupported = actionCatalogue.kinds.filterNot { actionDispatcher.supports(it.template) }
        require(unsupported.isEmpty()) {
            "No executor registered for: " + unsupported.joinToString { it.type }
        }

        // Cheap to leave running: it only suspends on the engine's event flow, which
        // produces nothing until a detector is started.
        automationCoordinator.start()

        // Mirror the Tuya keys into memory. See tuyaCredentials for why the client reads a
        // field rather than the flow.
        applicationScope.launch {
            settingsRepository.settings
                .map { it.tuya }
                .distinctUntilChanged()
                .collect { tuyaCredentials = it }
        }
    }

    /**
     * Persists an error from a component that is about to stop.
     *
     * The service cannot use its own scope for this — it may be being destroyed — so the
     * write is handed to the scope that owns the stores.
     */
    fun recordSensorModeError(error: SensorModeError) {
        applicationScope.launch { sensorModeStore.recordError(error) }
    }

    /**
     * Configuration to run instead of the saved settings, used while a calibration
     * result is being tried out. Null means "use what is saved".
     */
    private var trialConfiguration: DoubleClapConfiguration? = null

    /**
     * Starts detection with the user's current settings, or with a trial
     * configuration if one is in force.
     *
     * Lives here rather than in a ViewModel because the dashboard, the detector test
     * screen and calibration all start the same engine, and none of them should own
     * the settings-to-configuration mapping.
     */
    suspend fun startDetection() {
        val configurations = trialConfiguration
            ?.let { mapOf(TriggerId.DoubleClap to it) }
            ?: settingsRepository.settings.first().triggerConfigurations()
        triggerEngine.start(configurations)
    }

    fun stopDetection() {
        triggerEngine.stop()
    }

    /**
     * Reloads configuration into a running engine.
     *
     * Detectors receive their configuration once, when their flow is collected, so
     * changing sensitivity means restarting capture. Cheap — reopening the
     * microphone takes a fraction of a second — but not free, so callers should
     * apply a slider's final value rather than every intermediate one.
     */
    suspend fun restartDetection() {
        if (!triggerEngine.isRunning.value) return
        stopDetection()
        startDetection()
    }

    /**
     * Runs the full pipeline against an unsaved configuration so the user can try a
     * calibration result before committing to it.
     *
     * Deliberately goes through the engine rather than a private detector: the point
     * of the trial is that everything downstream behaves exactly as it will after
     * saving, vibration included.
     */
    suspend fun startTrial(configuration: DoubleClapConfiguration) {
        trialConfiguration = configuration
        stopDetection()
        startDetection()
    }

    /** Ends a trial and stops detection. The saved settings are untouched. */
    fun endTrial() {
        trialConfiguration = null
        stopDetection()
    }
}
