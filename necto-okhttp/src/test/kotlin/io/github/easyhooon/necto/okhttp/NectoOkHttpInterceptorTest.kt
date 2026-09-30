package io.github.easyhooon.necto.okhttp

import io.github.easyhooon.necto.plugins.NectoNetworkRecord
import io.github.easyhooon.necto.plugins.NectoNetworkReporting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NectoOkHttpInterceptorTest {
    private val server = MockWebServer().apply { start() }
    private val records = mutableListOf<NectoNetworkRecord>()
    private val reporter = object : NectoNetworkReporting {
        override fun report(record: NectoNetworkRecord) {
            synchronized(records) { records += record }
        }
    }
    private val client = OkHttpClient.Builder().addInterceptor(NectoOkHttpInterceptor(reporter)).build()

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun reportsPendingThenCompletedWithBodies() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))
        val request = Request.Builder()
            .url(server.url("/v1/items?page=2"))
            .header("X-Trace", "abc")
            .post("""{"name":"x"}""".toRequestBody("application/json".toMediaType()))
            .build()

        val body = client.newCall(request).execute().use { it.body!!.string() }
        assertEquals("""{"ok":true}""", body, "the app still reads the whole body")

        assertEquals(2, records.size)
        val (pending, completed) = records
        assertEquals(pending.id, completed.id)
        assertEquals(NectoNetworkRecord.State.PENDING, pending.state)
        assertNull(pending.requestBody)
        assertEquals(NectoNetworkRecord.State.COMPLETED, completed.state)
        assertEquals(201, completed.statusCode)
        assertEquals("items?page=2", completed.name)
        assertEquals("abc", completed.requestHeaders["X-Trace"])
        assertEquals("""{"name":"x"}""", completed.requestBody?.text)
        assertEquals("""{"ok":true}""", completed.responseBody?.text)
        assertEquals(11L, completed.responseByteCount)
        assertTrue(completed.durationMilliseconds!! >= 0)
    }

    @Test
    fun largeBodiesAreCappedButDeliveredWhole() {
        val big = "a".repeat(NectoNetworkRecord.Body.CAPTURE_LIMIT + 10)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(big))
        val body = client.newCall(Request.Builder().url(server.url("/big")).build()).execute().use { it.body!!.string() }
        assertEquals(big.length, body.length)
        val completed = records.last()
        assertTrue(completed.responseBody!!.isTruncated)
        assertEquals(NectoNetworkRecord.Body.CAPTURE_LIMIT, completed.responseBody!!.text!!.length)
    }

    @Test
    fun failuresAreReportedAndRethrown() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url(server.url("/down")).build()).execute()
        }
        assertEquals(NectoNetworkRecord.State.FAILED, records.last().state)
        assertTrue(records.last().errorSummary!!.isNotEmpty())
    }
}
