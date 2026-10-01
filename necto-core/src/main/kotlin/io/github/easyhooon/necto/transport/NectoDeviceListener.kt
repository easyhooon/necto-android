package io.github.easyhooon.necto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Accepts connections on a loopback TCP port inside the app.
 *
 * The socket roles are the opposite of the protocol roles: the app listens and the host
 * connects, even though the host drives the session. On Android the host reaches this
 * port through `adb forward tcp:9979 tcp:9979`, which lands on the Mac's loopback where
 * Necto already probes for simulator apps.
 */
public class NectoDeviceListener(port: Int = DEFAULT_PORT) {
    public class CannotListenException(reason: String, cause: Throwable? = null) :
        IOException("Cannot listen for Necto connections: $reason", cause)

    private val lock = Any()
    private var server: ServerSocket? = null
    private var storedPort: Int = port

    /** The port actually bound. Passing `0` asks the OS to pick one. */
    public val port: Int get() = synchronized(lock) { storedPort }

    public fun start() {
        synchronized(lock) {
            if (server != null) return
            val socket = ServerSocket()
            try {
                // Allows an immediate restart after the app relaunches.
                socket.reuseAddress = true
                // IPv4 on purpose, like the iOS SDK: Android's getLoopbackAddress() is ::1,
                // while adb forward dials 127.0.0.1 on the device.
                socket.bind(InetSocketAddress(IPV4_LOOPBACK, storedPort), 4)
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw CannotListenException(error.message ?: error.toString(), error)
            }
            storedPort = socket.localPort
            server = socket
        }
    }

    public fun stop() {
        val server = synchronized(lock) { server.also { this.server = null } }
        runCatching { server?.close() }
    }

    /**
     * Waits for the next connection. A blocked accept is woken by [stop], which is how
     * the runtime ends it; cancelling the caller alone does not.
     */
    public suspend fun accept(): NectoMessageSession {
        val server = synchronized(lock) { server } ?: throw CannotListenException("Listener is not running")
        return withContext(Dispatchers.IO) {
            try {
                NectoMessageSession(server.accept())
            } catch (error: IOException) {
                throw CannotListenException("Listener stopped", error)
            }
        }
    }

    public companion object {
        private val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** The port Necto uses. */
        public const val DEFAULT_PORT: Int = NectoTransportDefaults.DEVICE_PORT

        /** How many ports an app slides across when the first is taken. The host probes them all. */
        public const val PORT_SPAN: Int = NectoTransportDefaults.PORT_SPAN
    }
}
