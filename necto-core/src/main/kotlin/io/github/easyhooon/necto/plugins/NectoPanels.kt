package io.github.easyhooon.necto.plugins

import io.github.easyhooon.necto.sdk.NectoPluginPanel

/**
 * The panels Necto ships, packaged as Java resources under `necto/panels/<id>` with a
 * generated `files.txt` index. They are the iOS SDK's web panels, unchanged apart
 * from the manifests of Android-specific plugins.
 */
public object NectoPanels {
    public const val ROOT: String = "necto/panels"

    public fun resource(id: String): NectoPluginPanel =
        NectoPluginPanel.resources("$ROOT/$id", NectoPanels::class.java.classLoader!!)
}
