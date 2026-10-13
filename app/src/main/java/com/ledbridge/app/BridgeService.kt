package com.ledbridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service: keeps the strip (Bluetooth) and Sinric Pro (internet)
 * connections alive even when the screen is off.
 */
class BridgeService : Service() {
    companion object {
        const val ACTION_STOP = "com.ledbridge.app.STOP"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
    }

    private var ble: BleLed? = null
    private var sinric: SinricClient? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""

    private val tick = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        try {
            val n = buildNotification("Iniciando…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            BridgeState.log("No pude iniciar en primer plano: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!BridgeState.serviceRunning) startBridge()
        return START_STICKY
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- puente

    private fun startBridge() {
        val cfg = Config.load(this)
        if (!cfg.isComplete) {
            BridgeState.log("Faltan datos de configuración. Completa los 4 campos.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        BridgeState.serviceRunning = true
        BridgeState.log("Puente iniciado")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ledbridge:wake").apply {
            setReferenceCounted(false)
            acquire()
        }

        val appCtx = applicationContext
        val bleLed = BleLed(appCtx, cfg.bleName, Config.mac(appCtx)) { mac ->
            if (mac == null) Config.clearMac(appCtx) else Config.saveMac(appCtx, mac)
        }
        val controller = LightController(bleLed)
        val client = SinricClient(cfg.appKey, cfg.appSecret, cfg.deviceId, controller)

        ble = bleLed
        sinric = client
        BridgeState.controller = controller

        bleLed.start()
        client.start()
        handler.post(tick)
    }

    private fun stopBridge() {
        handler.removeCallbacks(tick)
        sinric?.stop()
        ble?.stop()
        sinric = null
        ble = null
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        wake = null
        if (BridgeState.serviceRunning) BridgeState.log("Puente detenido")
        BridgeState.reset()
    }

    // ---------------------------------------------------------------- notificación

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Puente de luces", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_bulb), "Detener", stopPi,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bulb)
            .setContentTitle("LedBridge")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification() {
        val text = "Tira: ${if (BridgeState.bleConnected) "conectada" else "sin conexión"}" +
            "  ·  Google: ${if (BridgeState.cloudConnected) "conectado" else "sin conexión"}"
        if (text == lastText) return
        lastText = text
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
