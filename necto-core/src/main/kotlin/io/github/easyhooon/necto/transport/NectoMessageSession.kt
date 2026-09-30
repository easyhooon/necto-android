package io.github.easyhooon.necto.transport

import io.github.easyhooon.necto.model.NectoJsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Values both ends of a Necto device connection must agree on. */
public object NectoTransportDefaults {
    public const val DEVICE_PORT: Int = 9979
    public const val PORT_SPAN: Int = 8
}

/**
 * A message oriented session over a byte stream: each message is framed as a 4 byte
 * big endian length followed by the payload, exactly like the Swift transport.
 */
public class NectoMessageSession(
    input: InputStream,
    output: OutputStream,
    private val onClose: () -> Unit = {},
) {
    public class MessageTooLargeException(size: Long) : IOException("Message of $size bytes exceeds the frame limit")

    public constructor(socket: Socket) : this(socket.getInputStream(), socket.getOutputStream(), closer(socket)) {
        socket.tcpNoDelay = true
    }

    private val input = DataInputStream(input)
    private val output = BufferedOutputStream(output)
    private val writeLock = Any()
    private val receiving = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    public val isClosed: Boolean get() = closed.get()

    /** Closes the stream, waking any pending read. Idempotent. */
    public fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    /** Sends one frame. Writes from several coroutines never interleave. */
    public suspend fun send(message: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (message.size > MAXIMUM_MESSAGE_BYTES) throw MessageTooLargeException(message.size.toLong())
        synchronized(writeLock) {
            if (closed.get()) throw IOException("The connection closed")
            try {
                val size = message.size
                output.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
                output.write(message)
                output.flush()
            } catch (error: IOException) {
                close()
                throw error
            }
        }
    }

    /** Reads one frame. The stream carries no message boundaries; the length prefix does. */
    public suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        check(receiving.compareAndSet(false, true)) { "Only one receiver may read a session at a time" }
        try {
            val length = input.readInt().toLong() and 0xFFFF_FFFFL
            if (length > MAXIMUM_MESSAGE_BYTES) {
                close()
                throw MessageTooLargeException(length)
            }
            ByteArray(length.toInt()).also { input.readFully(it) }
        } catch (error: EOFException) {
            throw IOException("The connection closed", error)
        } finally {
            receiving.set(false)
        }
    }

    public suspend fun send(value: NectoJsonValue): Unit = send(value.toJson().toByteArray(Charsets.UTF_8))

    public suspend fun receiveJson(): NectoJsonValue = NectoJsonValue.parse(receive().toString(Charsets.UTF_8))

    public companion object {
        private fun closer(socket: Socket): () -> Unit = {
            // Shutting down first wakes a read blocked on another thread.
            try { socket.shutdownInput() } catch (_: Exception) {}
            try { socket.shutdownOutput() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }

        /** Guards against a malformed length prefix allocating unbounded memory. */
        public const val MAXIMUM_MESSAGE_BYTES: Int = 32 * 1024 * 1024
    }
}
