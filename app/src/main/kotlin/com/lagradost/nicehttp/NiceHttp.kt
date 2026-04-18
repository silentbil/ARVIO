@file:Suppress("unused")

package com.lagradost.nicehttp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Clean-room re-implementation of CloudStream's NiceHttp client surface.
 * Real CloudStream `.cs3` plugins compile against the NiceHttp jar published
 * at github.com/Blatzar/NiceHttp; we mirror the observable method signatures
 * (class names, method names, default-parameter shapes) so plugin dex
 * references resolve at load time. Underlying transport is arvio's shared
 * OkHttpClient so DoH, connection pooling, logging and retries stay
 * consistent with the rest of the app.
 */

/**
 * Marker interface plugins implement to intercept response bodies before
 * they're handed back. We don't actually invoke it in the default get/post
 * paths — the real NiceHttp wires it into streaming JSON parsing that the
 * simple provider set doesn't need. Kept for signature compatibility.
 */
interface ResponseParser {
    fun <T : Any> readValue(content: String, kClass: Class<T>): T?
    fun writeValueAsString(obj: Any): String
}

/**
 * Wraps an OkHttp [Response] with the accessor shape plugins expect. The
 * body string is materialized on first access of [text] so plugins that use
 * multiple getters on the same response don't consume a already-closed body.
 */
class NiceResponse(
    private val response: Response,
    private val bodyString: String
) {
    val text: String get() = bodyString
    val code: Int get() = response.code
    val headers: Map<String, List<String>> get() = response.headers.toMultimap()
    val cookies: Map<String, String> get() = response.headers("Set-Cookie")
        .mapNotNull { it.substringBefore(';').split('=', limit = 2).takeIf { p -> p.size == 2 } }
        .associate { it[0].trim() to it[1].trim() }
    val url: String get() = response.request.url.toString()
    val okResponse: Response get() = response
    val isSuccessful: Boolean get() = response.isSuccessful
    val ok: Boolean get() = response.isSuccessful

    val document: Document by lazy { Jsoup.parse(bodyString) }

    /** The real NiceHttp exposes this getter for response-body parsing. */
    fun parsed(): Any? = null
}

/**
 * Suspend-friendly HTTP facade. All request-building convenience method
 * signatures (param ordering, default values) mirror the public NiceHttp
 * API so plugin bytecode that calls `get$default(...)` with the synthetic
 * Kotlin default-argument bitmask resolves cleanly.
 */
class Requests(
    private val baseClient: OkHttpClient,
    var defaultHeaders: Map<String, String> = emptyMap(),
    var defaultCookies: Map<String, String> = emptyMap(),
    var baseUrl: String? = null,
    var responseParser: ResponseParser? = null
) {
    /**
     * The Kotlin compiler emits `get$default` at the plugin call site. The
     * synthetic method dispatches here with named defaults in the canonical
     * order upstream NiceHttp uses.
     */
    @Suppress("LongParameterList")
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 60L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = null
    ): NiceResponse = execute(
        method = "GET",
        url = url,
        headers = headers,
        referer = referer,
        params = params,
        cookies = cookies,
        data = null,
        json = null,
        files = null,
        allowRedirects = allowRedirects,
        timeout = timeout,
        interceptor = interceptor,
        verify = verify
    )

    @Suppress("LongParameterList")
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        files: List<Any>? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 60L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = null
    ): NiceResponse = execute(
        method = "POST",
        url = url,
        headers = headers,
        referer = referer,
        params = params,
        cookies = cookies,
        data = data,
        json = json,
        files = files,
        allowRedirects = allowRedirects,
        timeout = timeout,
        interceptor = interceptor,
        verify = verify
    )

    @Suppress("LongParameterList")
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 60L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse = execute(
        method = "HEAD",
        url = url,
        headers = headers,
        referer = referer,
        params = params,
        cookies = cookies,
        data = null,
        json = null,
        files = null,
        allowRedirects = allowRedirects,
        timeout = timeout,
        interceptor = interceptor,
        verify = verify
    )

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        referer: String?,
        params: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>?,
        json: Any?,
        files: List<Any>?,
        allowRedirects: Boolean,
        timeout: Long,
        interceptor: Interceptor?,
        verify: Boolean
    ): NiceResponse = withContext(Dispatchers.IO) {
        val httpUrl = buildUrl(url, params) ?: error("Malformed URL: $url")
        val bodyMediaType = "application/json".toMediaTypeOrNull()

        val reqBuilder = Request.Builder().url(httpUrl)
        defaultHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        referer?.let { reqBuilder.header("Referer", it) }

        val cookiePairs = (defaultCookies + cookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (cookiePairs.isNotEmpty()) reqBuilder.header("Cookie", cookiePairs)

        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "HEAD" -> reqBuilder.head()
            "POST" -> {
                val body = when {
                    json != null -> {
                        val jsonString = when (json) {
                            is String -> json
                            else -> com.lagradost.cloudstream3.utils.AppUtils.toJson(json)
                        }
                        jsonString.toRequestBody(bodyMediaType)
                    }
                    data != null -> {
                        FormBody.Builder().apply {
                            data.forEach { (k, v) -> add(k, v) }
                        }.build()
                    }
                    else -> FormBody.Builder().build()
                }
                reqBuilder.post(body)
            }
            else -> error("Unsupported method: $method")
        }

        val clientBuilder = baseClient.newBuilder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
            .callTimeout(timeout, TimeUnit.SECONDS)
        interceptor?.let { clientBuilder.addInterceptor(it) }
        val client = clientBuilder.build()

        val call = client.newCall(reqBuilder.build())
        val response = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) =
                    cont.resumeWithException(e)

                override fun onResponse(call: okhttp3.Call, response: Response) =
                    cont.resume(response)
            })
        }
        val body = response.body?.string().orEmpty()
        NiceResponse(response, body)
    }

    private fun buildUrl(rawUrl: String, params: Map<String, String>): okhttp3.HttpUrl? {
        val normalized = if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl
        else baseUrl.orEmpty().trimEnd('/') + "/" + rawUrl.trimStart('/')
        val httpUrl = normalized.toHttpUrlOrNull() ?: return null
        if (params.isEmpty()) return httpUrl
        val builder = httpUrl.newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }
}
