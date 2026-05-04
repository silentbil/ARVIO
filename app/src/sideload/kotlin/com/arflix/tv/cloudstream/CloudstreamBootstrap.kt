package com.arflix.tv.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.setCloudstreamContext
import com.lagradost.cloudstream3.setCloudstreamHttpClient
import okhttp3.OkHttpClient

/**
 * Sideload-flavor init: bridges arvio's shared [OkHttpClient] into the
 * CloudStream plugin runtime's global HTTP accessor so `.cs3` plugins loaded
 * later hit the network through the same DNS-over-HTTPS, connection pool,
 * logging, and interceptor stack as the rest of the app.
 */
fun initCloudstream(client: OkHttpClient, context: Context? = null) {
    context?.let { setCloudstreamContext(it) }
    setCloudstreamHttpClient(client)
}
