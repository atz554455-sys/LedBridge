package com.ledbridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Single screen: connection settings, start/stop, quick tests and the log. */
class MainActivity : Activity() {
    private lateinit var etName: EditText
    private lateinit var etKey: EditText
    private lateinit var etSecret: EditText
    private lateinit var etDevice: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val cfg = Config.load(this)
        etName.setText(cfg.bleName)
        etKey.setText(cfg.appKey)
        etSecret.setText(cfg.appSecret)
        etDevice.setText(cfg.deviceId)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // ---------------------------------------------------------------- interfaz

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "LedBridge"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Puente Bluetooth ↔ Google Home para tu tira LED"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })

        etName = field(root, "Nombre Bluetooth de la tira (como en nRF Connect)", false)
        etKey = field(root, "Sinric Pro · App Key", false)
        etSecret = field(root, "Sinric Pro · App Secret", true)
        etDevice = field(root, "Sinric Pro · Device ID", false)

        root.addView(button("Guardar e iniciar") { onStartClicked() })
        root.addView(button("Detener") {
            stopService(Intent(this, BridgeService::class.java))
        })
        root.addView(button("Quitar ahorro de batería (importante)") { askBattery() })

        root.addView(TextView(this).apply {
            text = "Prueba rápida (solo Bluetooth, sin Google)"
            textSize = 13f
            setPadding(0, dp(16), 0, dp(4))
        })
        val tests = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tests.addView(testButton("Rojo") { it.setColor(255, 0, 0) })
        tests.addView(testButton("Verde") { it.setColor(0, 255, 0) })
        tests.addView(testButton("Azul") { it.setColor(0, 0, 255) })
        tests.addView(testButton("Blanco") { it.setColor(255, 255, 255) })
        tests.addView(testButton("Apagar") { it.setPower(false) })
        root.addView(tests)

        tvStatus = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(tvStatus)

        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(tvLog)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun field(parent: LinearLayout, hint: String, secret: Boolean): EditText {
        val et = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        parent.addView(et)
        return et
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun testButton(label: String, action: (LightController) -> Unit): View =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val c = BridgeState.controller
                if (c == null || !c.connected) {
                    toast("Primero inicia el puente y espera a que diga «Tira: conectado»")
                } else {
                    action(c)
                }
            }
        }

    private fun render() {
        val run = BridgeState.serviceRunning
        tvStatus.text = "Servicio: ${if (run) "ACTIVO" else "detenido"}\n" +
            "Tira (Bluetooth): ${if (BridgeState.bleConnected) "conectada ✔" else "sin conexión"}\n" +
            "Google Home (Sinric Pro): ${if (BridgeState.cloudConnected) "conectado ✔" else "sin conexión"}"
        val log = BridgeState.text()
        if (tvLog.text.toString() != log) tvLog.text = log
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- acciones

    private fun onStartClicked() {
        val cfg = Config(
            bleName = etName.text.toString().trim(),
            appKey = etKey.text.toString().trim(),
            appSecret = etSecret.text.toString().trim(),
            deviceId = etDevice.text.toString().trim(),
        )
        if (!cfg.isComplete) {
            toast("Completa los 4 campos")
            return
        }
        Config.save(this, cfg)

        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            launchService()
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, BridgeService::class.java))
    }

    private fun btPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun requiredPermissions(): List<String> =
        btPermissions() + if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val btOk = btPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (btOk) {
            launchService()
        } else {
            toast("Sin permiso de Bluetooth («Dispositivos cercanos») la app no puede funcionar")
        }
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Ya está sin restricción de batería ✔")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    companion object {
        private const val REQ_PERMS = 42
    }
}
