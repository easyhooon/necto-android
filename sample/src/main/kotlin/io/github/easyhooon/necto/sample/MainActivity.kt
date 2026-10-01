package io.github.easyhooon.necto.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.easyhooon.necto.plugins.NectoEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private val app get() = application as SampleApp
    private val counterKey = intPreferencesKey("counter")
    private val nameKey = stringPreferencesKey("name")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "adb forward tcp:9979 tcp:9979" }
        val counter = TextView(this)
        val name = EditText(this).apply { hint = "name" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(status)
            addView(counter)
            addView(name)
            addView(button("Save name") {
                scope.launch { settings.edit { it[nameKey] = name.text.toString() } }
            })
            addView(button("Increment counter") {
                scope.launch { settings.edit { it[counterKey] = (it[counterKey] ?: 0) + 1 } }
            })
            addView(button("GET httpbin.org/get") { request("https://httpbin.org/get?from=necto") })
            addView(button("GET 404") { request("https://httpbin.org/status/404") })
            addView(button("Log warning event") {
                app.events.report(NectoEvent(NectoEvent.Level.WARN, "Sample", "warning tapped"))
            })
            addView(button("Open Compose screen") {
                startActivity(android.content.Intent(this@MainActivity, ComposeActivity::class.java))
            })
        }
        setContentView(root)
        scope.launch { settings.data.map { it[counterKey] ?: 0 }.collect { counter.text = "counter = $it" } }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun request(url: String) {
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching { app.client.newCall(Request.Builder().url(url).build()).execute().use { it.code } }
                    .getOrElse { -1 }
            }
            app.events.report(NectoEvent(NectoEvent.Level.INFO, "Network", "$url -> $code"))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
