package io.github.easyhooon.necto.android

import android.content.Context
import android.content.SharedPreferences
import io.github.easyhooon.necto.model.NectoBridgeError
import io.github.easyhooon.necto.model.NectoBridgeErrorCode
import io.github.easyhooon.necto.model.NectoJsonValue
import io.github.easyhooon.necto.model.jsonArray
import io.github.easyhooon.necto.model.jsonObject
import io.github.easyhooon.necto.model.jsonOf
import io.github.easyhooon.necto.plugins.NectoPanels
import io.github.easyhooon.necto.sdk.NectoPlugin
import io.github.easyhooon.necto.sdk.NectoPluginPanel
import io.github.easyhooon.necto.sdk.NectoRegistrar
import java.io.File

/**
 * What the app has stored in `SharedPreferences`, read from Necto: the Android
 * counterpart of the iOS UserDefaults plugin, answering the same `preferences.*` contracts.
 *
 * `standard` is the default preferences file (`<package>_preferences`). Other files are
 * offered only when named in [names], or when [discoverAll] lists every file in
 * `shared_prefs`: a panel that can name any file can read anything the app stored.
 */
public class NectoSharedPreferencesPlugin(
    context: Context,
    private val names: List<String> = emptyList(),
    private val discoverAll: Boolean = false,
) : NectoPlugin {
    private val context = context.applicationContext

    override val id: String = "preferences"

    override val panel: NectoPluginPanel = NectoPanels.resource("preferences")

    override fun register(necto: NectoRegistrar) {
        necto.handle("preferences.list") { input ->
            val all = store(input["suite"]?.stringValue).all
            val prefix = input["prefix"]?.stringValue.orEmpty()
            val entries = all.entries
                .filter { prefix.isEmpty() || it.key.startsWith(prefix) }
                .sortedBy { it.key }
                .map { summary(it.key, it.value) }
            jsonObject("entries" to jsonArray(entries), "total" to jsonOf(all.size))
        }

        necto.handle("preferences.detail") { input ->
            val key = requiredKey(input)
            val value = store(input["suite"]?.stringValue).all[key]
                ?: throw NectoBridgeError(NectoBridgeErrorCode.OPERATION_UNAVAILABLE, "No key '$key'")
            jsonObject("entry" to detail(key, value))
        }

        necto.handle("preferences.suites") {
            jsonObject("suites" to jsonArray((listOf(STANDARD) + suites()).map(::jsonOf)))
        }

        // Writing into a store a running app is reading from can change what it does next.
        necto.handle("preferences.set") { input ->
            val key = requiredKey(input)
            val value = input["value"] ?: throw invalid("value is required")
            val preferences = store(input["suite"]?.stringValue)
            val editor = preferences.edit()
            write(editor, key, value, input["type"]?.stringValue, preferences.all[key])
            if (!editor.commit()) throw NectoBridgeError(NectoBridgeErrorCode.PROVIDER_FAILED, "The value could not be saved")
            jsonObject("entry" to detail(key, preferences.all[key]))
        }

        necto.handle("preferences.remove") { input ->
            val key = requiredKey(input)
            store(input["suite"]?.stringValue).edit().remove(key).commit()
            jsonObject("removed" to jsonOf(true))
        }
    }

    private val defaultName: String get() = "${context.packageName}_preferences"

    private fun suites(): List<String> {
        val discovered = if (discoverAll) {
            File(context.dataDir, "shared_prefs").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".xml") }
                ?.map { it.name.removeSuffix(".xml") }
                .orEmpty()
        } else {
            emptyList()
        }
        return (names + discovered).distinct().filter { it != defaultName }.sorted()
    }

    /** The default store, or a named one this plugin was told about. */
    private fun store(suite: String?): SharedPreferences {
        if (suite == null || suite == STANDARD) return context.getSharedPreferences(defaultName, Context.MODE_PRIVATE)
        if (suite !in suites()) throw invalid("This app does not offer the suite '$suite'")
        return context.getSharedPreferences(suite, Context.MODE_PRIVATE)
    }

    public companion object {
        public const val STANDARD: String = "standard"

        /** Enough of a value to recognise a row by. */
        internal const val PREVIEW_LIMIT: Int = 120

        private fun invalid(message: String) = NectoBridgeError(NectoBridgeErrorCode.INVALID_INPUT, message)

        private fun requiredKey(input: NectoJsonValue): String =
            input["key"]?.stringValue?.takeIf { it.isNotEmpty() } ?: throw invalid("key must be a non-empty string")

        internal fun summary(key: String, value: Any?): NectoJsonValue {
            val rendered = describe(value)
            return jsonObject(
                "key" to jsonOf(key),
                "type" to jsonOf(typeName(value)),
                "preview" to jsonOf(rendered.take(PREVIEW_LIMIT)),
                "isTruncated" to jsonOf(rendered.length > PREVIEW_LIMIT),
            )
        }

        internal fun detail(key: String, value: Any?): NectoJsonValue = jsonObject(
            "key" to jsonOf(key),
            "type" to jsonOf(typeName(value)),
            "value" to jsonOf(describe(value)),
        )

        /**
         * Named for the editor the panel opens: it knows Bool, Int, Double and Array. A Long
         * edits as an Int and a Float as a Double; [write] keeps the stored width.
         */
        internal fun typeName(value: Any?): String = when (value) {
            is Boolean -> "Bool"
            is Int, is Long -> "Int"
            is Float -> "Double"
            is Set<*> -> "Array"
            is String -> "String"
            else -> value?.javaClass?.simpleName ?: "Null"
        }

        /** One line for simple values, pretty JSON for a string set, which is also what the editor opens. */
        internal fun describe(value: Any?): String = when (value) {
            null -> "null"
            is String -> value
            is Set<*> -> {
                val items = value.map { it.toString() }.sorted()
                if (items.isEmpty()) "[\n\n]" else items.joinToString(",\n", "[\n", "\n]") { "  " + jsonOf(it).toJson() }
            }
            else -> value.toString()
        }

        /**
         * Stores [value] in the shape the key already has. JSON numbers are all doubles,
         * so without the stored type an Int would come back a Float, silently.
         */
        internal fun write(editor: SharedPreferences.Editor, key: String, value: NectoJsonValue, hint: String?, existing: Any?) {
            when (value) {
                is NectoJsonValue.Bool -> editor.putBoolean(key, value.value)
                is NectoJsonValue.Number -> {
                    val number = value.value
                    val integral = value.isInteger
                    when {
                        existing is Long -> editor.putLong(key, integerOrThrow(number, integral).toLong())
                        existing is Float -> editor.putFloat(key, number.toFloat())
                        existing is Int || (existing == null && (hint == "Int" || (hint == null && integral))) -> {
                            val integer = integerOrThrow(number, integral)
                            if (integer in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                                editor.putInt(key, integer.toInt())
                            } else {
                                editor.putLong(key, integer.toLong())
                            }
                        }
                        else -> editor.putFloat(key, number.toFloat())
                    }
                }
                is NectoJsonValue.Str -> editor.putString(key, value.value)
                is NectoJsonValue.Array -> {
                    val strings = value.values.map { it.stringValue ?: throw invalid("SharedPreferences stores sets of strings only") }
                    editor.putStringSet(key, strings.toSet())
                }
                is NectoJsonValue.Object -> throw invalid("SharedPreferences cannot store a dictionary")
                NectoJsonValue.Null -> editor.remove(key)
            }
        }

        private fun integerOrThrow(number: Double, integral: Boolean): Double {
            if (!integral) throw invalid("'$number' is not an integer")
            return number
        }
    }
}
