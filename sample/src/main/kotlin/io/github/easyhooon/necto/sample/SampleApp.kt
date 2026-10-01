package io.github.easyhooon.necto.sample

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.easyhooon.necto.android.NectoAndroid
import io.github.easyhooon.necto.android.NectoAndroidPlugins
import io.github.easyhooon.necto.okhttp.NectoOkHttpInterceptor
import io.github.easyhooon.necto.plugins.NectoEvent
import io.github.easyhooon.necto.plugins.NectoEventsPlugin
import io.github.easyhooon.necto.plugins.NectoNetworkPlugin
import okhttp3.OkHttpClient

val Context.settings by preferencesDataStore("settings")

class SampleApp : Application() {
    val events = NectoEventsPlugin()
    private val network = NectoNetworkPlugin()
    lateinit var client: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        val builder = OkHttpClient.Builder()
        if (BuildConfig.DEBUG) {
            NectoAndroid.start(
                this,
                NectoAndroidPlugins.defaults(this, events, network, dataStores = mapOf("settings" to settings)),
            )
            builder.addInterceptor(NectoOkHttpInterceptor(network))
            events.report(NectoEvent(NectoEvent.Level.INFO, "App", "started"))
        }
        client = builder.build()
    }
}
