package io.github.easyhooon.necto.sdk

import io.github.easyhooon.necto.model.NectoProtocol
import io.github.easyhooon.necto.transport.NectoDeviceListener
import kotlinx.coroutines.flow.StateFlow

/**
 * The entry point embedded in a connected app.
 *
 * Opens a loopback port for Necto, answers the handshake, and carries plugin messages in
 * both directions. Every capability an app exposes arrives as a [NectoPlugin].
 *
 * ```kotlin
 * NectoSDK.register(NectoNetworkPlugin())
 * NectoSDK.start()
 * ```
 *
 * On Android, prefer `NectoAndroid.start(context)`, which also supplies the app's identity.
 */
public object NectoSDK {
    public const val VERSION: String = "0.1.0"

    public sealed class Status {
        public data object Stopped : Status()
        public data class Listening(val port: Int) : Status()
        public data class Connected(val appBundleID: String) : Status()
        public data class Failed(val reason: String) : Status()
    }

    private val runtime = NectoSdkRuntime()

    /** The current connection state; collect it for later transitions. */
    public val status: StateFlow<Status> get() = runtime.status

    /** Who this app says it is in the handshake. Set before [start]. */
    public var identity: NectoAppIdentity
        get() = runtime.identity
        set(value) {
            runtime.identity = value
        }

    /**
     * Adds a plugin, at any point. One added while a host is attached joins that session.
     * Returns false when the ID or a contract is already registered; unregister first to
     * replace one.
     */
    public fun register(plugin: NectoPlugin): Boolean = runtime.register(plugin)

    /** Takes a plugin away and tells any attached host that it now offers nothing. */
    public fun unregister(id: String) {
        runtime.unregister(id)
    }

    public val plugins: List<NectoPlugin> get() = runtime.plugins

    /** Starts listening. Safe to call at app start up; it does not block the caller. */
    public fun start(port: Int = NectoDeviceListener.DEFAULT_PORT) {
        runtime.start(port)
    }

    public fun stop() {
        runtime.stop()
    }

    /** The wire protocol version this SDK speaks. */
    public val protocolVersion: Int get() = NectoProtocol.CURRENT_VERSION
}
