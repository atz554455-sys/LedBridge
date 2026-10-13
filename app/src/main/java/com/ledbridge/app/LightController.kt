package com.ledbridge.app

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Light state (power, color, brightness) and its translation into strip commands.
 *
 * Brightness is applied in software by scaling the RGB color, so it behaves the same on
 * every controller variant and does not depend on a dedicated brightness command.
 */
class LightController(private val ble: BleLed) {
    private var on = false
    private var r = 255
    private var g = 255
    private var b = 255

    @Volatile
    var brightness = 100
        private set

    val connected: Boolean
        get() = ble.connected

    @Synchronized
    fun setPower(value: Boolean) {
        if (value) {
            ble.send(BleLed.ON)
            on = true
            sendColor()
        } else {
            ble.send(BleLed.OFF)
            on = false
        }
    }

    @Synchronized
    fun setBrightness(percent: Int) {
        brightness = percent.coerceIn(1, 100)
        ensureOn()
        sendColor()
    }

    @Synchronized
    fun setColor(red: Int, green: Int, blue: Int) {
        r = red.coerceIn(0, 255)
        g = green.coerceIn(0, 255)
        b = blue.coerceIn(0, 255)
        ensureOn()
        sendColor()
    }

    /** Warm to cool white; the value is clamped to 1000-10000 K. */
    @Synchronized
    fun setColorTemperature(kelvin: Int) {
        val t = kelvin.coerceIn(1000, 10000) / 100.0
        val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val green = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
        else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val blue = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        r = red.roundToInt().coerceIn(0, 255)
        g = green.roundToInt().coerceIn(0, 255)
        b = blue.roundToInt().coerceIn(0, 255)
        ensureOn()
        sendColor()
    }

    private fun ensureOn() {
        if (!on) {
            ble.send(BleLed.ON)
            on = true
        }
    }

    private fun sendColor() {
        val k = brightness / 100.0
        ble.send(BleLed.color((r * k).roundToInt(), (g * k).roundToInt(), (b * k).roundToInt()))
    }
}
