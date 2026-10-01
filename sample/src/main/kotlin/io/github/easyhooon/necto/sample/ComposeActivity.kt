package io.github.easyhooon.necto.sample

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.easyhooon.necto.plugins.NectoEvent

/**
 * A Compose screen for trying UI Control on Compose content. A full-window view that
 * takes no touches sits on top, as edge-to-edge's system bar protection does, so the
 * screen also shows that such overlays do not hide what lies below.
 */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val events = (application as SampleApp).events
        val compose = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    var count by rememberSaveable { mutableIntStateOf(0) }
                    var name by rememberSaveable { mutableStateOf("") }
                    var enabled by rememberSaveable { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Compose count = $count")
                        Button(
                            onClick = {
                                count++
                                events.report(NectoEvent(NectoEvent.Level.INFO, "Compose", "count = $count"))
                            },
                            modifier = Modifier.testTag("increment"),
                        ) { Text("Increment") }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            modifier = Modifier.fillMaxWidth().testTag("name"),
                        )
                        Text("Hello, ${name.ifEmpty { "nobody" }}")
                        Switch(checked = enabled, onCheckedChange = { enabled = it }, modifier = Modifier.testTag("toggle"))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp).testTag("list")) {
                            items(50) { index -> Text("Row $index", modifier = Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        }
        val overlay = View(this) // Takes no touches, like androidx.core's ProtectionLayout.
        setContentView(
            FrameLayout(this).apply {
                addView(compose, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            },
        )
    }
}
