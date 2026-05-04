package com.lagradost.cloudstream3.network

import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.cookies
import com.lagradost.nicehttp.getHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Compatibility interceptor expected by several CloudStream extensions.
 */
@AnyThread
class DdosGuardKiller(private val alwaysBypass: Boolean = false) : Interceptor {
    private val savedCookiesMap = mutableMapOf<String, Map<String, String>>()
    private var ddosBypassPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking(Dispatchers.IO) {
        val request = chain.request()
        if (alwaysBypass) return@runBlocking bypassDdosGuard(request)

        val response = chain.proceed(request)
        if (response.code == 403) {
            response.close()
            return@runBlocking bypassDdosGuard(request)
        }
        response
    }

    private suspend fun bypassDdosGuard(request: Request): Response {
        ddosBypassPath = ddosBypassPath ?: runCatching {
            Regex("'(.*?)'").find(app.get("https://check.ddos-guard.net/check.js").text)
                ?.groupValues
                ?.getOrNull(1)
        }.getOrNull()

        val cookies = savedCookiesMap[request.url.host]
            ?: runCatching {
                val base = "${request.url.scheme}://${request.url.host}${ddosBypassPath.orEmpty()}"
                Requests().get(base).cookies.also { savedCookiesMap[request.url.host] = it }
            }.getOrDefault(emptyMap())

        val headers = getHeaders(request.headers.toMap(), null, cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).execute()
    }
}
