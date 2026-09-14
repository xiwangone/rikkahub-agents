package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class SensorToolTest {

    // Enumerating sensors and the successful read path require SensorManager — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `sensor read requires a type`() {
        sensorReadPayload(NULL_CONTEXT, null, null)
    }

    @Test
    fun `sensor read returns error envelope for unknown sensor type`() {
        // Unknown-type validation runs before getSystemService, so a null Context is fine.
        val payload = sensorReadPayload(NULL_CONTEXT, "nonsense", null)
        val text = payload.toString()
        assertTrue(
            "expected unknown-sensor-type error, got: $text",
            text.contains("error") && text.contains("unknown sensor type")
        )
    }
}
