package com.unboundaistudio

/**
 * Engine selection policy for Boob's V6-1.
 * An engine is eligible only if it is installed/configured, supports the requested
 * operation, and is explicitly authorized for network/cost use.
 */
internal object HybridEngineSelector {
    enum class Mode { AUTO, OFFLINE, ONLINE }
    enum class Task { TEXT_IMAGE, IMAGE_EDIT, IMAGE_VIDEO }
    enum class Location { DEVICE, NETWORK }
    data class Engine(
        val id: String,
        val location: Location,
        val tasks: Set<Task>,
        val ready: Boolean,
        val supportsPromptedMotion: Boolean = false,
        val requiresPaidService: Boolean = false
    )
    data class Request(
        val mode: Mode,
        val task: Task,
        val internetAvailable: Boolean,
        val paidServiceApproved: Boolean = false,
        val needsPromptedMotion: Boolean = false
    )
    fun candidates(request: Request, engines: List<Engine>): List<Engine> {
        return engines.filter { engine ->
            engine.ready &&
                request.task in engine.tasks &&
                (request.mode != Mode.OFFLINE || engine.location == Location.DEVICE) &&
                (request.mode != Mode.ONLINE || engine.location == Location.NETWORK) &&
                (engine.location != Location.NETWORK || request.internetAvailable) &&
                (!engine.requiresPaidService || request.paidServiceApproved) &&
                (!request.needsPromptedMotion || engine.supportsPromptedMotion)
        }.sortedWith(compareBy<Engine> {
            when (request.mode) {
                Mode.AUTO -> if (it.location == Location.DEVICE) 0 else 1
                Mode.OFFLINE, Mode.ONLINE -> 0
            }
        }.thenBy { it.id })
    }
}
