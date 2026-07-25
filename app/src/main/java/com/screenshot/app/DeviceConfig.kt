package com.screenshot.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named device configuration with a watermark position.
 * e.g. { name: "行车记录仪", position: BOTTOM_RIGHT }
 */
data class DeviceConfig(
    val id: Long,
    val name: String,
    val position: WatermarkPosition
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("position", position.name)
    }

    companion object {
        fun fromJson(json: JSONObject): DeviceConfig {
            val posName = json.optString("position", WatermarkPosition.BOTTOM_RIGHT.name)
            return DeviceConfig(
                id = json.getLong("id"),
                name = json.optString("name", ""),
                position = try { WatermarkPosition.valueOf(posName) } catch (_: Exception) { WatermarkPosition.BOTTOM_RIGHT }
            )
        }
    }
}

/**
 * Persists device configs to SharedPreferences.
 * Tracks the last selected config ID.
 */
class DeviceConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("device_configs", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_CONFIGS = "configs"
        const val KEY_SELECTED_ID = "selected_id"
    }

    /** Load all saved configs. */
    fun loadAll(): List<DeviceConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i -> DeviceConfig.fromJson(arr.getJSONObject(i)) }
    }

    /** Save all configs. */
    private fun saveAll(configs: List<DeviceConfig>) {
        val arr = JSONArray()
        configs.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }

    /** Add a new config. Returns the added config with a generated ID. */
    fun add(name: String, position: WatermarkPosition): DeviceConfig {
        val configs = loadAll().toMutableList()
        val id = System.currentTimeMillis()
        val config = DeviceConfig(id, name, position)
        configs.add(config)
        saveAll(configs)
        return config
    }

    /** Remove a config by ID. */
    fun remove(id: Long) {
        val configs = loadAll().filter { it.id != id }
        saveAll(configs)
        // If the removed one was selected, clear selection
        if (getSelectedId() == id) {
            prefs.edit().remove(KEY_SELECTED_ID).apply()
        }
    }

    /** Update an existing config. */
    fun update(id: Long, name: String, position: WatermarkPosition) {
        val configs = loadAll().map {
            if (it.id == id) it.copy(name = name, position = position) else it
        }
        saveAll(configs)
    }

    /** Get selected config ID. Returns -1 if none selected. */
    fun getSelectedId(): Long {
        return prefs.getLong(KEY_SELECTED_ID, -1L)
    }

    /** Set selected config ID. */
    fun setSelectedId(id: Long) {
        prefs.edit().putLong(KEY_SELECTED_ID, id).apply()
    }

    /** Get the currently selected config, or null. */
    fun getSelected(): DeviceConfig? {
        val id = getSelectedId()
        if (id == -1L) return null
        return loadAll().find { it.id == id }
    }
}
