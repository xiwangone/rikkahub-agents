package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolErrors

fun torchTool(context: Context): Tool = Tool(
    name = "set_torch",
    description = "Turn the camera flashlight (torch) on or off.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("on", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true to turn the torch on, false to turn it off")
                })
            },
            required = listOf("on")
        )
    },
    execute = {
        val params = it.jsonObject
        val on = params["on"]?.jsonPrimitive?.booleanOrNull
            ?: error("on is required")
        val cm = context.getSystemService(CameraManager::class.java)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "invalid_argument", message = "camera service unavailable", hint = "Check the parameter values and retry with corrected arguments.").toString()
                )
            )
        val flashId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@Tool listOf(
            UIMessagePart.Text(
                ToolErrors.envelopeFor(error = "invalid_argument", message = "no flash unit available", hint = "Check the parameter values and retry with corrected arguments.").toString()
            )
        )
        val payload = try {
            cm.setTorchMode(flashId, on)
            buildJsonObject {
                put("success", true)
                put("on", on)
            }
        } catch (e: CameraAccessException) {
            ToolErrors.envelopeFor(error = "invalid_argument", message = "torch unavailable: ${e.message ?: "camera access error"}", hint = "Check the parameter values and retry with corrected arguments.")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
