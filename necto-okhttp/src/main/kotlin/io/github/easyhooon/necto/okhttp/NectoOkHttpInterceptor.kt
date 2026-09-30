package io.github.easyhooon.necto.okhttp

import io.github.easyhooon.necto.plugins.NectoNetworkPlugin
import io.github.easyhooon.necto.plugins.NectoNetworkRecord
import io.github.easyhooon.necto.plugins.NectoNetworkReporting
import io.github.easyhooon.necto.sdk.NectoPlugin
import io.github.easyhooon.necto.sdk.NectoPluginPanel
import io.github.easyhooon.necto.sdk.NectoRegistrar
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID

/**
 * The network plugin with OkHttp capture ready to wire, the counterpart of the iOS
 * `URLSessionNetworkPlugin`.
 *
 * ```kotlin
 * val network = OkHttpNetworkPlugin()
 * NectoSDK.register(network)
 * val client = OkHttpClient.Builder().addInterceptor(network.interceptor).build()
 * ```
 *
 * An app that reports some requests itself as well reaches the plugin through [network].
 */
public class OkHttpNetworkPlugin(public val network: NectoNetworkPlugin = NectoNetworkPlugin()) : NectoPlugin {
    override val id: String get() = network.id
    override val panel: NectoPluginPanel get() = network.panel

    /** Add as an application interceptor, so bodies are seen decompressed. */
    public val interceptor: NectoOkHttpInterceptor = NectoOkHttpInterceptor(network)

    override fun register(necto: NectoRegistrar) {
        network.register(necto)
    }
}

/**
 * Captures OkHttp traffic and reports it to whatever takes reports.
 *
 * It only observes: the request goes through unchanged and the response is handed
 * back as received. The response body is read with `peekBody`, so the app still
 * reads it whole; at most [NectoNetworkRecord.Body.CAPTURE_LIMIT] bytes are copied.
 */
public class NectoOkHttpInterceptor(private val reporter: NectoNetworkReporting) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id = UUID.randomUUID().toString().uppercase()
        val startedAt = System.currentTimeMillis()
        val startedNanos = System.nanoTime()

        fun record(
            state: NectoNetworkRecord.State,
            response: Response? = null,
            error: Throwable? = null,
            responseBody: NectoNetworkRecord.Body? = null,
        ): NectoNetworkRecord = NectoNetworkRecord(
            id = id,
            method = request.method,
            url = request.url.toString(),
            startedAtMilliseconds = startedAt.toDouble(),
            state = state,
            statusCode = response?.code,
            durationMilliseconds = if (state == NectoNetworkRecord.State.PENDING) null else (System.nanoTime() - startedNanos) / 1_000_000.0,
            responseByteCount = if (state == NectoNetworkRecord.State.COMPLETED) responseBody?.byteCount ?: 0 else null,
            errorSummary = error?.let { it.message ?: it.toString() },
            requestHeaders = headers(request.headers),
            responseHeaders = response?.let { headers(it.headers) }.orEmpty(),
            // A pending record carries no body yet: sending it twice would double the
            // traffic for large uploads.
            requestBody = if (state == NectoNetworkRecord.State.PENDING) null else requestBody(request),
            responseBody = responseBody,
        )

        safely { reporter.report(record(NectoNetworkRecord.State.PENDING)) }

        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            safely { reporter.report(record(NectoNetworkRecord.State.FAILED, error = error)) }
            throw error
        }

        safely {
            val body = responseBody(response)
            reporter.report(record(NectoNetworkRecord.State.COMPLETED, response = response, responseBody = body))
        }
        return response
    }

    /** Capture never breaks the request it observes. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private fun headers(headers: Headers): Map<String, String> =
        headers.names().associateWith { name -> headers.values(name).joinToString(", ") }

    private fun requestBody(request: Request): NectoNetworkRecord.Body? {
        val body = request.body ?: return null
        // Duplex and one-shot bodies can only be written once, and that write is the app's.
        if (body.isDuplex() || body.isOneShot()) return null
        val buffer = Buffer()
        body.writeTo(buffer)
        val size = buffer.size
        val bytes = buffer.readByteArray(minOf(size, NectoNetworkRecord.Body.CAPTURE_LIMIT.toLong()))
        if (bytes.isEmpty()) return null
        return NectoNetworkRecord.Body.of(bytes, body.contentType()?.toString(), byteCount = size)
    }

    private fun responseBody(response: Response): NectoNetworkRecord.Body? {
        val body = response.body ?: return null
        val limit = NectoNetworkRecord.Body.CAPTURE_LIMIT.toLong()
        // One byte past the limit says whether the body was cut.
        val peeked = response.peekBody(limit + 1).bytes()
        if (peeked.isEmpty()) return null
        val declared = body.contentLength()
        val byteCount = when {
            declared >= 0 -> declared
            else -> peeked.size.toLong()
        }
        val type = response.header("Content-Type") ?: body.contentType()?.toString()
        return NectoNetworkRecord.Body.of(peeked.copyOf(minOf(peeked.size, limit.toInt())), type, byteCount = maxOf(byteCount, peeked.size.toLong()))
    }
}
