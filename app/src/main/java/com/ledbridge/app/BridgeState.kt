package com.ledbridge.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** State shared between the service and the screen (log + status flags). */
object BridgeState {
    @Volatile var serviceRunning = false
    @Volatile var bleConnected = false
    @Volatile var cloudConnected = false
    @Volatile var controller: LightController? = null

    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(msg: String) {
        lines.add("${fmt.format(Date())}  $msg")
        while (lines.size > 80) lines.removeAt(0)
        Log.d("LedBridge", msg)
    }

    /** Last lines of the log, newest first. */
    @Synchronized
    fun text(): String = lines.takeLast(40).reversed().joinToString("\n")

    fun reset() {
        serviceRunning = false
        bleConnected = false
        cloudConnected = false
        controller = null
    }
}
