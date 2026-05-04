package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

/**
 * Minimal upstream CloudStream app facade used by community extensions.
 *
 * ARVIO does not run CloudStream's Application class, but many extensions
 * reference CloudStreamApp during load() for context and simple persisted
 * settings. Keeping this surface available lets those extensions register
 * their MainAPI providers instead of failing with NoClassDefFoundError.
 */
open class CloudStreamApp : Application() {
    companion object {
        private const val TAG = "CloudStreamApp"
        private const val PREFS_NAME = "cloudstream_plugin_compat"
        private val gson = Gson()

        @JvmStatic
        var context: Context? = null
            set(value) {
                field = value?.applicationContext ?: value
            }

        @JvmStatic
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        @JvmStatic
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            val ctx = context ?: return
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }.onFailure {
                Log.w(TAG, "Unable to open browser for $url", it)
            }
        }

        @JvmStatic
        fun openBrowser(url: String, activity: FragmentActivity?) {
            openBrowser(url, fallbackWebView = false, fragment = activity?.supportFragmentManager?.fragments?.lastOrNull())
        }

        @JvmStatic
        fun <T> getKeyClass(path: String, valueType: Class<T>): T? {
            val value = getRaw(path) ?: return null
            return decodeValue(value, valueType)
        }

        @JvmStatic
        fun <T> setKeyClass(path: String, value: T) {
            setRaw(path, encodeValue(value))
        }

        @JvmStatic
        fun removeKeys(folder: String): Int? {
            val prefs = prefs() ?: return null
            val prefix = "$folder/"
            val keys = prefs.all.keys.filter { it.startsWith(prefix) }
            prefs.edit().apply {
                keys.forEach(::remove)
            }.apply()
            return keys.size
        }

        @JvmStatic
        fun <T> setKey(path: String, value: T) {
            setRaw(path, encodeValue(value))
        }

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) {
            setRaw("$folder/$path", encodeValue(value))
        }

        @JvmStatic
        fun <T> getKey(path: String, defVal: T?): T? {
            val value = getRaw(path) ?: return defVal
            return decodeValue(value, defVal?.let { it::class.java }) ?: defVal
        }

        @JvmStatic
        fun <T> getKey(path: String): T? {
            val value = getRaw(path) ?: return null
            return decodeValue(value, null)
        }

        @JvmStatic
        fun <T> getKey(folder: String, path: String): T? {
            val value = getRaw("$folder/$path") ?: return null
            return decodeValue(value, null)
        }

        @JvmStatic
        fun <T> getKey(folder: String, path: String, defVal: T?): T? {
            val value = getRaw("$folder/$path") ?: return defVal
            return decodeValue(value, defVal?.let { it::class.java }) ?: defVal
        }

        @JvmStatic
        fun getKeys(folder: String): List<String>? {
            val prefs = prefs() ?: return null
            val prefix = "$folder/"
            return prefs.all.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
        }

        @JvmStatic
        fun removeKey(folder: String, path: String) {
            prefs()?.edit()?.remove("$folder/$path")?.apply()
        }

        @JvmStatic
        fun removeKey(path: String) {
            prefs()?.edit()?.remove(path)?.apply()
        }

        private fun prefs() = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun getRaw(path: String): String? = prefs()?.getString(path, null)

        private fun setRaw(path: String, value: String) {
            prefs()?.edit()?.putString(path, value)?.apply()
        }

        private fun encodeValue(value: Any?): String = gson.toJson(value)

        @Suppress("UNCHECKED_CAST")
        private fun <T> decodeValue(value: String, valueType: Class<*>?): T? {
            return runCatching {
                when (valueType) {
                    String::class.java -> value.trim('"') as T
                    Int::class.java, java.lang.Integer::class.java -> gson.fromJson(value, Int::class.java) as T
                    Long::class.java, java.lang.Long::class.java -> gson.fromJson(value, Long::class.java) as T
                    Float::class.java, java.lang.Float::class.java -> gson.fromJson(value, Float::class.java) as T
                    Double::class.java, java.lang.Double::class.java -> gson.fromJson(value, Double::class.java) as T
                    Boolean::class.java, java.lang.Boolean::class.java -> gson.fromJson(value, Boolean::class.java) as T
                    null -> gson.fromJson<T>(value, object : TypeToken<T>() {}.type)
                    else -> gson.fromJson(value, valueType) as T
                }
            }.recoverCatching { error ->
                if (error is JsonSyntaxException && valueType == String::class.java) value as T else throw error
            }.getOrNull()
        }
    }
}
