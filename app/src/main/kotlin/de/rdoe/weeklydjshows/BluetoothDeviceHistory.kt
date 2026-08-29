package de.rdoe.weeklydjshows

import android.content.Context

internal data class RememberedBluetoothDevice(
    val address: String,
    val name: String,
    val lastConnectedAtEpochMs: Long,
)

/**
 * Small local catalogue for the Bluetooth automation screen.
 *
 * Some Android/One UI builds expose an empty bonded-device set while the adapter is off. Keeping
 * the last successfully read name/address pairs makes the user's automation choices visible in
 * that state. Connection timestamps are learned from ACL_CONNECTED and provide a public-API-safe
 * "recently used first" order; Android doesn't expose the Settings app's private recency value.
 */
internal object BluetoothDeviceHistory {
    private const val FILE = "weekly_dj_bluetooth_devices"
    private const val NAME_PREFIX = "name::"
    private const val LAST_CONNECTED_PREFIX = "last_connected::"

    fun recordKnown(context: Context, address: String, name: String) {
        if (address.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val key = NAME_PREFIX + address
        val existing = prefs.getString(key, null)
        val stableName = name.takeIf { it.isNotBlank() && it != "Unbenanntes Gerät" }
            ?: existing
            ?: "Unbenanntes Gerät"
        prefs.edit().putString(key, stableName).apply()
    }

    fun recordConnection(context: Context, address: String, name: String) {
        if (address.isBlank()) return
        recordKnown(context, address, name)
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_CONNECTED_PREFIX + address, System.currentTimeMillis())
            .apply()
    }

    fun remembered(context: Context): List<RememberedBluetoothDevice> {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.all.keys
            .asSequence()
            .filter { it.startsWith(NAME_PREFIX) }
            .mapNotNull { key ->
                val address = key.removePrefix(NAME_PREFIX).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: "Unbenanntes Gerät"
                RememberedBluetoothDevice(
                    address = address,
                    name = name,
                    lastConnectedAtEpochMs = prefs.getLong(LAST_CONNECTED_PREFIX + address, 0L),
                )
            }
            .toList()
    }
}
