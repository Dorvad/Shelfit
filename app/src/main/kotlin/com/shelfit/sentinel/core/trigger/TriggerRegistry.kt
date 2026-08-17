package com.shelfit.sentinel.core.trigger

/**
 * The set of detectors this build knows about.
 *
 * A new trigger type becomes available to the whole app by being added to the
 * list handed to this registry — see `AppContainer`. Nothing else needs editing.
 */
class TriggerRegistry(detectors: List<TriggerDetector>) {

    val detectors: List<TriggerDetector> = detectors.toList()

    private val byId: Map<TriggerId, TriggerDetector> =
        detectors.associateBy { it.trigger.id }

    init {
        require(byId.size == detectors.size) { "Duplicate TriggerId in registry" }
    }

    val triggers: List<Trigger> get() = detectors.map { it.trigger }

    fun detector(id: TriggerId): TriggerDetector? = byId[id]

    fun trigger(id: TriggerId): Trigger? = byId[id]?.trigger
}
