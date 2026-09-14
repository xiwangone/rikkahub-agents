package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Device status reader, grouped by kind so the tool surface stays small.
 *
 * Each kind delegates to the per-area implementation ([batteryPayload], [audioPayload], …) and the
 * payload shape is unchanged — only the entry point is consolidated. `sensors` lists every sensor
 * when `sensor` is omitted and reads one sensor when it is given.
 */
private val DEVICE_INFO_KINDS = listOf("battery", "audio", "telephony", "wifi", "storage", "sensors")

internal suspend fun readDeviceInfo(context: Context, kind: String, sensor: String?, durationMs: Int?): JsonObject =
    when (kind) {
        "battery" -> batteryPayload(context)
        "audio" -> audioPayload(context)
        "telephony" -> telephonyPayload(context)
        "wifi" -> wifiPayload(context)
        "storage" -> storagePayload(context)
        "sensors" -> if (sensor.isNullOrBlank()) sensorsPayload(context) else sensorReadPayload(context, sensor, durationMs)
        else -> buildJsonObject {
            put("error", "unknown kind '$kind'")
            put("hint", "kind must be one of: ${DEVICE_INFO_KINDS.joinToString(" | ")}")
        }
    }

fun deviceInfoTool(context: Context): Tool = Tool(
    name = "device_info",
    description = """
        Read device status. Choose one kind: battery (charge level, health, temperature),
        audio (streams and volumes), telephony (SIM and network), wifi (connection and SSID),
        storage (internal and external space), or sensors (list every sensor, or read one sensor
        by passing "sensor").
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("description", "What to read: ${DEVICE_INFO_KINDS.joinToString(" | ")}")
                    put("enum", buildJsonArray { DEVICE_INFO_KINDS.forEach { add(it) } })
                })
                put("sensor", buildJsonObject {
                    put("type", "string")
                    put("description", "Sensor name, sensors kind only, e.g. \"accelerometer\". Omit it to list all sensors.")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Sample window in ms for a sensor read, sensors kind only. Default 200, max 5000.")
                })
            },
            required = listOf("kind")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val kind = params["kind"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase()
        val sensor = params["sensor"]?.jsonPrimitive?.contentOrNull
        val durationMs = params["duration_ms"]?.jsonPrimitive?.intOrNull
        listOf(UIMessagePart.Text(readDeviceInfo(context, kind, sensor, durationMs).toString()))
    }
)
