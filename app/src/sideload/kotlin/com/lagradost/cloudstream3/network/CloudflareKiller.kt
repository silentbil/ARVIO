package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import com.lagradost.nicehttp.getHeaders
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        private const val TAG = "CloudflareKiller"
        private val ERROR_CODES = setOf(403, 503)
        private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";")
                .associate { item ->
                    val split = item.split("=", limit = 2)
                    (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
                }
                .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
        }
    }

    private val savedCookies = mutableMapOf<String, Map<String, String>>()

    init {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()
        return getHeaders(userAgentHeaders, null, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val cachedCookies = savedCookies[request.url.host]

        if (cachedCookies != null) {
            return@runBlocking proceed(request, cachedCookies)
        }

        val response = chain.proceed(request)
        val isCloudflareChallenge =
            response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES

        if (!isCloudflareChallenge) {
            return@runBlocking response
        }

        response.close()
        bypassCloudflare(request)?.let {
            Log.d(TAG, "Succeeded bypassing Cloudflare: ${request.url}")
            return@runBlocking it
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? = runCatching {
        CookieManager.getInstance()?.getCookie(url)
    }.getOrNull()

    private fun trySolveWithSavedCookies(request: Request): Boolean {
        val cookie = getWebViewCookie(request.url.toString()) ?: return false
        val solved = cookie.contains("cf_clearance")
        if (solved) {
            savedCookies[request.url.host] = parseCookieMap(cookie)
        }
        return solved
    }

    private suspend fun proceed(
        request: Request,
        cookies: Map<String, String>
    ): Response {
        val userAgentHeaders = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()
        val headers = getHeaders(
            request.headers.toMap() + userAgentHeaders,
            null,
            cookies + request.cookies
        )
        return app.baseClient
            .newCall(request.newBuilder().headers(headers).build())
            .await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading WebView to solve Cloudflare for ${request.url}")
            WebViewResolver(
                interceptUrl = Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(request.url.toString()) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}
