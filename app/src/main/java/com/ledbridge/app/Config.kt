package com.ledbridge.app

import android.content.Context

/** Settings the user types on the main screen. */
data class Config(
    val bleName: String,
    val appKey: String,
    val appSecret: String,
    val deviceId: String,
) {
    val isComplete: Boolean
        get() = bleName.isNotBlank() && appKey.isNotBlank() &&
            appSecret.isNotBlank() && deviceId.isNotBlank()

    companion object {
        private const val FILE = "ledbridge"
        private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun load(c: Context): Config {
            val p = sp(c)
            return Config(
                bleName = p.getString("bleName", "BLEDDM") ?: "BLEDDM",
                appKey = p.getString("appKey", "") ?: "",
                appSecret = p.getString("appSecret", "") ?: "",
                deviceId = p.getString("deviceId", "") ?: "",
            )
        }

        fun save(c: Context, cfg: Config) {
            val old = load(c)
            val name = cfg.bleName.trim()
            val e = sp(c).edit()
                .putString("bleName", name)
                .putString("appKey", cfg.appKey.trim())
                .putString("appSecret", cfg.appSecret.trim())
                .putString("deviceId", cfg.deviceId.trim())
            // If the strip name changed, forget the saved address.
            if (!old.bleName.equals(name, ignoreCase = true)) e.remove("mac")
            e.apply()
        }

        /** Bluetooth address of the strip (saved automatically after the first connection). */
        fun mac(c: Context): String? = sp(c).getString("mac", null)

        fun saveMac(c: Context, mac: String) {
            sp(c).edit().putString("mac", mac).apply()
        }

        fun clearMac(c: Context) {
            sp(c).edit().remove("mac").apply()
        }
    }
}
