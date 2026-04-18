@file:Suppress("unused", "FunctionName")

package com.lagradost.cloudstream3

import com.lagradost.nicehttp.Requests
import okhttp3.OkHttpClient

/**
 * Global `app` accessor used by every CloudStream plugin to perform HTTP.
 * Real plugins call `com.lagradost.cloudstream3.MainActivityKt.getApp()`,
 * which returns a shared `Requests` instance. We initialize it lazily with
 * a sensible default; `ArflixApplication` replaces it with the real arvio
 * OkHttpClient at startup via [setCloudstreamHttpClient].
 */
@Volatile
private var cloudstreamRequests: Requests = Requests(OkHttpClient.Builder().build())

fun getApp(): Requests = cloudstreamRequests

/**
 * Inject arvio's shared OkHttpClient so the plugin's HTTP calls go through
 * the same DNS-over-HTTPS, connection pool, and logging interceptor as the
 * rest of the app. Called from `ArflixApplication.onCreate()`.
 */
fun setCloudstreamHttpClient(client: OkHttpClient) {
    cloudstreamRequests = Requests(client)
}
