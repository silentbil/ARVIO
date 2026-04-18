package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

/**
 * Clean-room `BasePlugin` — the concrete subclass inside every `.cs3` is what
 * the runtime instantiates. Plugins register their `MainAPI` implementations
 * via [registerMainAPI] from inside `load(context)`.
 *
 * Upstream CloudStream exposes more hooks (preferences, asset loading, etc.);
 * we only surface the ones observed in the published plugin ecosystem.
 */
open class BasePlugin {
    /** APIs the plugin has exposed during `load()`. Read-only from outside. */
    private val registered: MutableList<MainAPI> = mutableListOf()

    val __arvio_registered: List<MainAPI> get() = registered.toList()

    /**
     * Called by the runtime right after instantiation. Plugin subclasses put
     * `registerMainAPI(MyProvider())` calls inside the override.
     */
    open fun load(context: Context) {}

    /** Called on teardown (uninstall / swap). Most plugins don't override. */
    open fun beforeUnload() {}

    /**
     * The public API plugins invoke. Kept non-final so subclasses can
     * decorate (e.g., tagging) without breaking the contract.
     */
    fun registerMainAPI(api: MainAPI) {
        registered.add(api)
    }

    /** Old alias some plugins call instead of [registerMainAPI]. */
    fun registerExtractorAPI(api: MainAPI) {
        registered.add(api)
    }
}
