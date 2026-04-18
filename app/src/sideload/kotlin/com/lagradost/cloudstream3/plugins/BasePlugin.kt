package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

/**
 * Vendored BasePlugin — in upstream CloudStream 4.4.0 this sits in the `app`
 * module rather than the library module. We host it inside arvio because
 * plugins extend it as their entry point. Call `registerMainAPI(api)` from
 * inside `load()` to expose your provider to arvio's runtime.
 */
open class BasePlugin {
    private val registered: MutableList<MainAPI> = mutableListOf()

    val __arvio_registered: List<MainAPI> get() = registered.toList()

    open fun load(context: Context) {}

    open fun beforeUnload() {}

    fun registerMainAPI(api: MainAPI) {
        registered.add(api)
    }

    fun registerExtractorAPI(api: MainAPI) {
        registered.add(api)
    }
}
