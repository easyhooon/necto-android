package io.github.easyhooon.necto.sample

import android.content.Context
import io.github.easyhooon.necto.android.NectoAndroidPlugins
import io.github.easyhooon.necto.plugins.NectoEvent
import io.github.easyhooon.necto.plugins.NectoEventsPlugin
import io.github.easyhooon.necto.plugins.NectoPerformancePlugin

/**
 * Gives the Events and Performance plugins something real to show.
 *
 * The app explicitly opts into Necto's process sampler.
 */
object ExampleTelemetry {
    val events = NectoEventsPlugin()

    /** The sampler reads this process through a Context, so it is made once the app has one. */
    fun performance(context: Context): NectoPerformancePlugin = NectoAndroidPlugins.performance(context)

    fun start() {
        events.report(NectoEvent(NectoEvent.Level.INFO, "Boot", "Necto Example started"))
    }

    /** Called from wherever the app already knows something happened. */
    fun log(level: NectoEvent.Level, tag: String, message: String, detail: Map<String, String> = emptyMap()) {
        events.report(NectoEvent(level, tag, message, detail))
    }
}
