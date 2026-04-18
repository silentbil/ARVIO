package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis

/**
 * Library-flavored plugin base, matching upstream recloudstream/cloudstream
 * master `library/src/commonMain/.../plugins/BasePlugin.kt`. Plugins compiled
 * against the modern KMP library extend this. `load()` takes no arguments
 * (Context isn't in commonMain); the app-module `Plugin` subclass — for
 * older CS 4.x-era plugins — provides the `load(context: Context)` hook.
 *
 * `registerMainAPI()` pushes into the global `APIHolder.allProviders` list;
 * the runtime snapshots that list before/after `load()` to identify what the
 * plugin exposed.
 */
abstract class BasePlugin {
    fun registerMainAPI(element: MainAPI) {
        element.sourcePlugin = this.filename
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.add(element)
        }
        APIHolder.addPluginMapping(element)
    }

    fun registerExtractorAPI(element: ExtractorApi) {
        element.sourcePlugin = this.filename
        extractorApis.add(element)
    }

    open fun beforeUnload() {}

    open fun load() {}

    /** Full file path to the plugin. Set by the runtime after instantiation. */
    var filename: String? = null

    /** Legacy alias kept for plugins that predate the rename. */
    @Suppress("PropertyName")
    @Deprecated("Renamed to filename", ReplaceWith("filename"))
    var __filename: String?
        get() = filename
        set(value) { filename = value }
}
