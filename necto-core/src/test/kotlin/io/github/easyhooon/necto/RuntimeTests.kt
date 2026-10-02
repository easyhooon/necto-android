package io.github.easyhooon.necto

import io.github.easyhooon.necto.model.NectoBridgeError
import io.github.easyhooon.necto.model.NectoBridgeErrorCode
import io.github.easyhooon.necto.model.NectoEnvelope
import io.github.easyhooon.necto.model.NectoHandshakeAck
import io.github.easyhooon.necto.model.NectoHandshakeHello
import io.github.easyhooon.necto.model.NectoJsonValue
import io.github.easyhooon.necto.model.NectoOperationKind
import io.github.easyhooon.necto.model.NectoPanelArchive
import io.github.easyhooon.necto.sdk.NectoPluginPanel
import io.github.easyhooon.necto.model.NectoPluginCancellation
import io.github.easyhooon.necto.model.NectoPluginInvocation
import io.github.easyhooon.necto.model.NectoPluginRegistration
import io.github.easyhooon.necto.model.NectoPluginResult
import io.github.easyhooon.necto.model.jsonObject
import io.github.easyhooon.necto.model.jsonOf
import io.github.easyhooon.necto.plugins.NectoEvent
import io.github.easyhooon.necto.plugins.NectoEventsPlugin
import io.github.easyhooon.necto.plugins.NectoNetworkPlugin
import io.github.easyhooon.necto.plugins.NectoNetworkRecord
import io.github.easyhooon.necto.sdk.NectoAppIdentity
import io.github.easyhooon.necto.sdk.NectoPlugin
import io.github.easyhooon.necto.sdk.NectoRegistrar
import io.github.easyhooon.necto.sdk.NectoSDK
import io.github.easyhooon.necto.sdk.NectoSdkRuntime
import io.github.easyhooon.necto.transport.NectoMessageSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeTests {
    private val runtime = NectoSdkRuntime()
    private val streamCancelled = AtomicBoolean(false)

    private val testPlugin = object : NectoPlugin {
        override val id = "test"
        override fun register(necto: NectoRegistrar) {
            necto.handle("test.echo") { input -> jsonObject("echo" to input) }
            necto.handle("test.fail") { throw NectoBridgeError(NectoBridgeErrorCode.INVALID_INPUT, "nope") }
            necto.handle("test.crash") { error("boom") }
            necto.handle("test.nan") { jsonOf(Double.NaN) }
            necto.stream("test.ticks") { _, out ->
                repeat(3) { out.send(jsonOf(it)) }
            }
            necto.stream("test.forever") { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    streamCancelled.set(true)
                }
            }
        }
    }

    init {
        runtime.identity = object : NectoAppIdentity {
            override val appBundleID = "com.example.app"
            override val appName = "Example"
            override val appVersion = "1.0"
            override val deviceName = "Test"
            override val osVersion = "15"
            override val simulatorID = "android:test"
        }
    }

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    /** Plays the Mac: connects, answers the hello, and collects the registrations. */
    private inner class FakeHost(port: Int) {
        val session = NectoMessageSession(Socket(InetAddress.getLoopbackAddress(), port))
        val registrations = LinkedHashMap<String, NectoPluginRegistration>()
        lateinit var hello: NectoHandshakeHello

        suspend fun handshake() {
            hello = NectoHandshakeHello.fromJson(session.receiveJson())
            session.send(NectoHandshakeAck.evaluate(hello).toJson())
        }

        suspend fun next(): NectoEnvelope {
            while (true) {
                val envelope = NectoEnvelope.fromJson(session.receiveJson())
                if (envelope.type == NectoEnvelope.Kind.PLUGIN_REGISTER) {
                    val registration = NectoPluginRegistration.fromJson(envelope.payload)
                    registrations[registration.pluginID] = registration
                    continue
                }
                return envelope
            }
        }

        suspend fun result(): NectoPluginResult {
            val envelope = next()
            assertEquals(NectoEnvelope.Kind.PLUGIN_RESULT, envelope.type)
            return NectoPluginResult.fromJson(envelope.payload)
        }

        suspend fun invoke(id: String, name: String, kind: NectoOperationKind = NectoOperationKind.ONCE, input: NectoJsonValue = jsonObject()) {
            val invocation = NectoPluginInvocation(id, "necto.device.$name", 1, kind, input)
            session.send(NectoEnvelope(NectoEnvelope.Kind.PLUGIN_INVOKE, invocation.toJson()).toJson())
        }

        suspend fun cancel(id: String) {
            session.send(NectoEnvelope(NectoEnvelope.Kind.PLUGIN_CANCEL, NectoPluginCancellation(id).toJson()).toJson())
        }

        /** Registrations arrive on their own writer, so wait for them explicitly. */
        suspend fun awaitRegistration(pluginID: String): NectoPluginRegistration {
            while (pluginID !in registrations) {
                val envelope = NectoEnvelope.fromJson(session.receiveJson())
                assertEquals(NectoEnvelope.Kind.PLUGIN_REGISTER, envelope.type)
                val registration = NectoPluginRegistration.fromJson(envelope.payload)
                registrations[registration.pluginID] = registration
            }
            return registrations.getValue(pluginID)
        }
    }

    private suspend fun connect(): FakeHost {
        runtime.start(0)
        val listening = runtime.status.first { it is NectoSDK.Status.Listening } as NectoSDK.Status.Listening
        val host = FakeHost(listening.port)
        host.handshake()
        runtime.status.first { it is NectoSDK.Status.Connected }
        return host
    }

    @Test
    fun handshakeCarriesIdentityAndRegistrations() = runBlocking<Unit> {
        withTimeout(10_000) {
            runtime.register(testPlugin)
            val host = connect()
            assertEquals("com.example.app", host.hello.appBundleID)
            assertEquals("android:test", host.hello.simulatorID)
            assertEquals(NectoSDK.VERSION, host.hello.sdkVersion)

            val registration = host.awaitRegistration("test")
            val names = registration.catalog.bridges.map { it.identity }.toSet()
            assertTrue("necto.device.test.echo@1" in names)
            assertEquals(NectoOperationKind.STREAM, registration.catalog.bridges.first { it.name == "necto.device.test.ticks" }.kind)

            // A plugin added mid-session joins it; removal is an empty catalog.
            runtime.register(NectoEventsPlugin())
            val events = host.awaitRegistration("event-log")
            assertNotNull(events.panel?.contentHash)
            host.registrations.remove("event-log")
            runtime.unregister("event-log")
            assertTrue(host.awaitRegistration("event-log").catalog.bridges.isEmpty())
        }
    }

    @Test
    fun onceCallsAnswerWithOutputOrError() = runBlocking<Unit> {
        withTimeout(10_000) {
            runtime.register(testPlugin)
            val host = connect()

            host.invoke("1", "test.echo", input = jsonObject("x" to jsonOf(1)))
            assertEquals(jsonObject("echo" to jsonObject("x" to jsonOf(1))), host.result().output)

            host.invoke("2", "test.fail")
            assertEquals(NectoBridgeErrorCode.INVALID_INPUT, host.result().error?.code)

            host.invoke("3", "test.crash")
            val crash = host.result()
            assertEquals(NectoBridgeErrorCode.PROVIDER_FAILED, crash.error?.code)
            assertEquals("boom", crash.error?.message)

            host.invoke("4", "test.nan")
            assertEquals("The result could not be encoded as JSON", host.result().error?.message)

            host.invoke("5", "test.missing")
            assertEquals(NectoBridgeErrorCode.OPERATION_UNAVAILABLE, host.result().error?.code)
        }
    }

    @Test
    fun streamsSendEventsThenAFinalResultAndStopOnCancel() = runBlocking<Unit> {
        withTimeout(10_000) {
            runtime.register(testPlugin)
            val host = connect()

            host.invoke("s", "test.ticks", NectoOperationKind.STREAM)
            val results = List(4) { host.result() }
            assertEquals(listOf(jsonOf(0), jsonOf(1), jsonOf(2), null), results.map { it.output })
            assertEquals(listOf(false, false, false, true), results.map { it.isFinal })

            host.invoke("f", "test.forever", NectoOperationKind.STREAM)
            delay(200)
            host.cancel("f")
            withTimeout(2_000) { while (!streamCancelled.get()) delay(20) }
        }
    }

    @Test
    fun panelsAreServedByTheSdk() = runBlocking<Unit> {
        withTimeout(10_000) {
            val events = NectoEventsPlugin()
            runtime.register(events)
            val host = connect()
            val stamp = host.awaitRegistration("event-log").panel!!

            host.invoke("p", "plugins.assets", input = jsonObject("pluginID" to jsonOf("event-log")))
            val archive = NectoPanelArchive.fromJson(host.result().output!!)
            assertEquals(stamp.contentHash, archive.contentHash)
            assertTrue(archive.files.any { it.path == "manifest.json" })
            assertFalse(archive.files.any { it.path == "files.txt" })

            host.invoke("q", "plugins.assets", input = jsonObject("pluginID" to jsonOf("test")))
            assertEquals(NectoBridgeErrorCode.OPERATION_UNAVAILABLE, host.result().error?.code)
        }
    }

    @Test
    fun panelsAreReadOnceWhenAHostFirstNeedsThem() = runBlocking<Unit> {
        withTimeout(10_000) {
            val real = NectoEventsPlugin().panel!!
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val registeringThread = Thread.currentThread()
            var readingThread: Thread? = null
            val plugin = object : NectoPlugin {
                override val id = "lazy-panel"
                override val panel = NectoPluginPanel {
                    reads.incrementAndGet()
                    readingThread = Thread.currentThread()
                    real.read()
                }
                override fun register(necto: NectoRegistrar) = Unit
            }

            runtime.register(plugin)
            assertEquals(0, reads.get(), "registering does not read the panel")

            val host = connect()
            assertNotNull(host.awaitRegistration("lazy-panel").panel)
            assertEquals(1, reads.get())
            assertTrue(readingThread !== registeringThread, "the panel is read off the registering thread")

            host.invoke("p", "plugins.assets", input = jsonObject("pluginID" to jsonOf("lazy-panel")))
            assertNotNull(host.result().output)
            assertEquals(1, reads.get(), "the panel is read once and then held")
        }
    }

    @Test
    fun eventsAndNetworkPluginsReportToObservers() = runBlocking<Unit> {
        withTimeout(10_000) {
            val events = NectoEventsPlugin()
            val network = NectoNetworkPlugin()
            runtime.register(events)
            runtime.register(network)
            val host = connect()

            host.invoke("o", "events.observe", NectoOperationKind.STREAM)
            delay(200)
            events.report(NectoEvent(NectoEvent.Level.WARN, "Cache", "Evicted", mapOf("count" to "240")))
            val observed = host.result()
            assertFalse(observed.isFinal)
            assertEquals(jsonOf("Evicted"), observed.output?.get("event")?.get("message"))
            val eventID = observed.output!!["event"]!!["id"]!!.stringValue!!

            host.invoke("d", "events.detail", input = jsonObject("eventID" to jsonOf(eventID)))
            assertEquals(jsonOf("240"), host.result().output?.get("event")?.get("detail")?.get("count"))

            network.report(NectoNetworkRecord("r", "GET", "https://example.com/v1/items?page=2", 1.0, NectoNetworkRecord.State.PENDING))
            network.report(
                NectoNetworkRecord(
                    "r", "GET", "https://example.com/v1/items?page=2", 1.0, NectoNetworkRecord.State.COMPLETED,
                    statusCode = 200, responseBody = NectoNetworkRecord.Body.of("{}".toByteArray(), "application/json"),
                ),
            )
            host.invoke("l", "network-records.list")
            val records = host.result().output!!["records"]!!.arrayValue!!
            assertEquals(1, records.size)
            assertEquals(jsonOf("items?page=2"), records[0]["name"])
            assertEquals(jsonOf(200), records[0]["statusCode"])

            host.invoke("rd", "network-records.detail", input = jsonObject("recordID" to jsonOf("r")))
            assertEquals(jsonOf("{}"), host.result().output!!["record"]!!["responseBody"]!!["text"])
        }
    }

    @Test
    fun hostDisconnectReturnsToListening() = runBlocking<Unit> {
        withTimeout(10_000) {
            val host = connect()
            host.session.close()
            runtime.status.first { it is NectoSDK.Status.Listening }
            // The listener takes the next host.
            val listening = runtime.status.value as NectoSDK.Status.Listening
            val second = FakeHost(listening.port)
            second.handshake()
            runtime.status.first { it is NectoSDK.Status.Connected }
        }
    }
}
