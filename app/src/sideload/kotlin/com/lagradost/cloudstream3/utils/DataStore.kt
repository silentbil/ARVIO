package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

const val DOWNLOAD_HEADER_CACHE = "download_header_cache"
const val DOWNLOAD_HEADER_CACHE_BACKUP = "BACKUP_download_header_cache"
const val DOWNLOAD_EPISODE_CACHE = "download_episode_cache"
const val DOWNLOAD_EPISODE_CACHE_BACKUP = "BACKUP_download_episode_cache"
const val VIDEO_PLAYER_BRIGHTNESS = "video_player_alpha_key"
const val USER_SELECTED_HOMEPAGE_API = "home_api_used"
const val USER_PROVIDER_API = "user_custom_sites"
const val PREFERENCES_NAME = "rebuild_preference"

class PreferenceDelegate<T : Any>(
    val key: String,
    val default: T
) {
    private var cache: T? = null

    operator fun getValue(self: Any?, property: kotlin.reflect.KProperty<*>): T {
        return cache ?: default
    }

    operator fun setValue(self: Any?, property: kotlin.reflect.KProperty<*>, value: T?) {
        cache = value
    }
}

data class Editor(
    val editor: SharedPreferences.Editor
) {
    fun <T> setKeyRaw(path: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> editor.putBoolean(path, value)
            is Int -> editor.putInt(path, value)
            is String -> editor.putString(path, value)
            is Float -> editor.putFloat(path, value)
            is Long -> editor.putLong(path, value)
            is Set<*> -> editor.putStringSet(path, value.filterIsInstance<String>().toSet())
            else -> editor.putString(path, DataStore.mapper.writeValueAsString(value))
        }
    }

    fun apply() {
        editor.apply()
    }
}

object DataStore {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun Context.getSharedPrefs(): SharedPreferences = getPreferences(this)

    fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences("${PREFERENCES_NAME}_default", Context.MODE_PRIVATE)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun editor(context: Context, isEditingAppSettings: Boolean = false): Editor {
        val prefs = if (isEditingAppSettings) context.getDefaultSharedPrefs() else context.getSharedPrefs()
        return Editor(prefs.edit())
    }

    fun Context.getKeys(folder: String): List<String> {
        return getSharedPrefs().all.keys.filter { it.startsWith(folder) }
    }

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.containsKey(folder: String, path: String): Boolean {
        return containsKey(getFolderName(folder, path))
    }

    fun Context.containsKey(path: String): Boolean {
        return getSharedPrefs().contains(path)
    }

    fun Context.removeKey(path: String) {
        getSharedPrefs().edit().remove(path).apply()
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys("$folder/")
        getSharedPrefs().edit().apply {
            keys.forEach(::remove)
        }.apply()
        return keys.size
    }

    fun <T> Context.setKey(path: String, value: T) {
        getSharedPrefs().edit()
            .putString(path, mapper.writeValueAsString(value))
            .apply()
    }

    fun <T> Context.getKey(path: String, valueType: Class<T>): T? {
        val json = getSharedPrefs().getString(path, null) ?: return null
        return runCatching { mapper.readValue(json, valueType) }.getOrNull()
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    inline fun <reified T> String.toKotlinObject(): T {
        return mapper.readValue(this, T::class.java)
    }

    fun <T> String.toKotlinObject(valueType: Class<T>): T {
        return mapper.readValue(this, valueType)
    }

    inline fun <reified T> Context.getKey(path: String, defVal: T?): T? {
        val json = getSharedPrefs().getString(path, null) ?: return defVal
        return runCatching { json.toKotlinObject<T>() }.getOrNull() ?: defVal
    }

    inline fun <reified T> Context.getKey(path: String): T? {
        return getKey(path, null)
    }

    inline fun <reified T> Context.getKey(folder: String, path: String): T? {
        return getKey(getFolderName(folder, path), null)
    }

    inline fun <reified T> Context.getKey(folder: String, path: String, defVal: T?): T? {
        return getKey(getFolderName(folder, path), defVal) ?: defVal
    }
}
