package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolErrors

private fun networkTypeName(type: Int): String = when (type) {
    1 -> "GPRS"
    2 -> "EDGE"
    3 -> "UMTS"
    4 -> "CDMA"
    8 -> "HSDPA"
    9 -> "HSUPA"
    10 -> "HSPA"
    13 -> "LTE"
    15 -> "HSPAP"
    18 -> "IWLAN"
    20 -> "NR"
    else -> "unknown"
}

private fun phoneTypeName(type: Int): String = when (type) {
    0 -> "none"
    1 -> "gsm"
    2 -> "cdma"
    3 -> "sip"
    else -> "unknown"
}

internal fun telephonyPayload(context: Context): JsonObject {
        val payload = if (!PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.READ_PHONE_STATE)
            )
        ) {
            ToolErrors.envelopeFor(error = "permission_denied", message = "permission READ_PHONE_STATE not granted", hint = "Grant the required permission or choose a different target.")
        } else {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (tm == null) {
                ToolErrors.envelopeFor(error = "invalid_argument", message = "telephony service unavailable", hint = "Check the parameter values and retry with corrected arguments.")
            } else {
                try {
                    val hasSim = tm.simState == TelephonyManager.SIM_STATE_READY
                    val networkType = try {
                        tm.dataNetworkType
                    } catch (_: SecurityException) {
                        @Suppress("DEPRECATION")
                        tm.networkType
                    }
                    buildJsonObject {
                        put("has_sim", hasSim)
                        put("sim_operator", tm.simOperator ?: "")
                        put("sim_country", tm.simCountryIso ?: "")
                        put("network_operator", tm.networkOperator ?: "")
                        put("network_country", tm.networkCountryIso ?: "")
                        put("network_type", networkTypeName(networkType))
                        put("phone_type", phoneTypeName(tm.phoneType))
                    }
                } catch (_: SecurityException) {
                    ToolErrors.envelopeFor(error = "permission_denied", message = "permission READ_PHONE_STATE not granted", hint = "Grant the required permission or choose a different target.")
                }
            }
        }
    return payload
}
