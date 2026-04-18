package com.lagradost.api

/**
 * arvio stand-in for the upstream `com.lagradost.api.BuildConfig` generated
 * by buildkonfig. We hardcode the fields the vendored library reads — all
 * it needs is a DEBUG flag; debug-logging helpers gate on it.
 */
object BuildConfig {
    const val DEBUG: Boolean = false
}
