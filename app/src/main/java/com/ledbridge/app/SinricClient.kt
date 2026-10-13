package com.ledbridge.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sinric Pro client (wss://ws.sinric.pro). It registers as the "Smart Light Bulb" device
 * created in the Sinric Pro portal; Google Home sends commands through the cloud and this
 * class forwards them to the strip over Bluetooth.
 *
 * Responses are signed with HMAC-SHA256 (key = App Secret) over the exact "payload" text,
 * and that same text is what gets sent.
 *
 * Note: messages sent to the cloud are plain ASCII on purpose, so the signature matches
 * byte for byte.
 */
class SinricClient(
    private val appKey: String,
    private val appSecret: String,
    private val deviceId: String,
    private val light: LightController,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(120, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var running = false
    private var ws: WebSocket? = null

    fun start() {
        running = true
        handler.post { connect() }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val w = ws
        ws = null
        BridgeState.cloudConnected = false
        try {
            w?.close(1000, "stop")
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun connect() {
        if (!running) return
        BridgeState.log("Conectando con Sinric Pro…")
        val req = Request.Builder()
            .url("wss://ws.sinric.pro")
            .addHeader("appkey", appKey)
            .addHeader("deviceids", deviceId)
            .addHeader("platform", "Android")
            .addHeader("SDKVersion", "1.0.0")
            .build()
        ws = http.newWebSocket(req, listener)
    }

    private fun lost(webSocket: WebSocket, why: String) {
        if (!running) return
        if (ws != null && webSocket !== ws) return
        ws = null
        BridgeState.cloudConnected = false
        BridgeState.log("Sinric Pro desconectado: $why")
        if (running) handler.postDelayed({ connect() }, 5000)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            BridgeState.cloudConnected = true
            BridgeState.log("Sinric Pro conectado ✔")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(webSocket, text)
            } catch (e: Exception) {
                BridgeState.log("Error procesando orden: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lost(webSocket, "cerrado ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val why = if (code == 401 || code == 403) {
                "rechazado ($code): revisa App Key y Device ID"
            } else {
                t.message ?: "error de red"
            }
            lost(webSocket, why)
        }
    }

    // ---------------------------------------------------------------- órdenes

    private class Result(val ok: Boolean, val message: String, val value: JSONObject)

    private fun valueOf(req: JSONObject): JSONObject = when (val v = req.opt("value")) {
        is JSONObject -> v
        is String -> try {
            JSONObject(v)
        } catch (e: Exception) {
            JSONObject()
        }
        else -> JSONObject()
    }

    private fun handle(webSocket: WebSocket, text: String) {
        val root = JSONObject(text)
        if (root.has("timestamp")) return // the server sends its clock right after connecting
        val req = root.optJSONObject("payload") ?: return
        if (req.optString("type") != "request") return
        val reqDevice = req.optString("deviceId")
        if (reqDevice.isNotEmpty() && reqDevice != deviceId) return

        val action = req.optString("action")
        val value = valueOf(req)
        BridgeState.log("Google → $action $value")

        val result = execute(action, value)

        val payload = JSONObject()
        payload.put("action", action)
        payload.put("clientId", req.optString("clientId"))
        payload.put("createdAt", System.currentTimeMillis() / 1000)
        payload.put("deviceId", deviceId)
        payload.put("message", result.message)
        payload.put("replyToken", req.optString("replyToken"))
        payload.put("scope", "device")
        payload.put("success", result.ok)
        payload.put("type", "response")
        payload.put("value", result.value)
        if (req.has("instanceId")) payload.put("instanceId", req.optString("instanceId"))

        val payloadText = payload.toString()
        val signature = sign(payloadText)
        val message = "{\"header\":{\"payloadVersion\":2,\"signatureVersion\":1}," +
            "\"payload\":$payloadText,\"signature\":{\"HMAC\":\"$signature\"}}"
        webSocket.send(message)
        if (!result.ok) BridgeState.log("Respondí con error: ${result.message}")
    }

    private fun execute(action: String, v: JSONObject): Result {
        if (!light.connected) {
            return Result(false, "Bluetooth controller not connected", JSONObject())
        }
        return when (action) {
            "setPowerState" -> {
                val on = v.optString("state").equals("On", ignoreCase = true)
                light.setPower(on)
                Result(true, "OK", JSONObject().put("state", if (on) "On" else "Off"))
            }
            "setBrightness" -> {
                light.setBrightness(v.optInt("brightness", 100))
                Result(true, "OK", JSONObject().put("brightness", light.brightness))
            }
            "adjustBrightness" -> {
                light.setBrightness(light.brightness + v.optInt("brightnessDelta", 0))
                Result(true, "OK", JSONObject().put("brightness", light.brightness))
            }
            "setColor" -> {
                val c = v.optJSONObject("color")
                    ?: return Result(false, "Missing color", JSONObject())
                val r = c.optInt("r")
                val g = c.optInt("g")
                val b = c.optInt("b")
                light.setColor(r, g, b)
                Result(
                    true,
                    "OK",
                    JSONObject().put("color", JSONObject().put("r", r).put("g", g).put("b", b)),
                )
            }
            "setColorTemperature" -> {
                val k = v.optInt("colorTemperature", 2700)
                light.setColorTemperature(k)
                Result(true, "OK", JSONObject().put("colorTemperature", k))
            }
            else -> Result(false, "Unsupported action", JSONObject())
        }
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
