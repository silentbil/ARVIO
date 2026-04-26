package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import java.net.URI

class Techinmind : GDMirrorbot() {
    override var name = "Techinmind Cloud AIO"
    override var mainUrl = "https://stream.techinmind.space"
    override var requiresReferer = true
}

open class GDMirrorbot : ExtractorApi() {
    override var name = "GDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (sid, host) = if (!url.contains("key=")) {
            Pair(url.substringAfterLast("embed/"), getBaseUrl(app.get(url).url))
        } else {
            var pageText = app.get(url).text
            val finalId = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val myKey = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val idType = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"
            val baseUrl = Regex("""let\s+baseUrl\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val hostUrl = baseUrl?.let { getBaseUrl(it) }

            if (finalId != null && myKey != null) {
                val apiUrl = if (url.contains("/tv/")) {
                    val season = Regex("""/tv/\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: "1"
                    val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
                    "$mainUrl/myseriesapi?tmdbid=$finalId&season=$season&epname=$episode&key=$myKey"
                } else {
                    "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                }
                pageText = app.get(apiUrl).text
            }

            val jsonObject = runCatching { JSONObject(pageText) }.getOrNull() ?: return
            val embedId = url.substringAfterLast("/")
            val dataArray = runCatching { jsonObject.getJSONArray("data") }.getOrNull()
            val sidValue = dataArray
                ?.takeIf { it.length() > 0 }
                ?.let { runCatching { it.getJSONObject(0).optString("fileslug") }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: embedId

            Pair(sidValue, hostUrl)
        }

        val postData = mapOf("sid" to sid)
        val responseText = app.post("$host/embedhelper.php", data = postData).text

        val root = runCatching { JSONObject(responseText) }.getOrNull() ?: return

        val siteUrlsObj = runCatching { root.getJSONObject("siteUrls") }.getOrNull() ?: return
        val siteFriendlyNamesObj = runCatching { root.getJSONObject("siteFriendlyNames") }.getOrNull()

        val mresultRaw = root.opt("mresult") ?: return
        val decodedMresult: JSONObject = when {
            mresultRaw is JSONObject -> mresultRaw
            mresultRaw is String -> runCatching {
                JSONObject(base64Decode(mresultRaw))
            }.getOrElse {
                Log.e("GDMirrorbot", "Failed to decode mresult: $it")
                return
            }
            else -> return
        }

        val siteKeys = buildList {
            val iter = siteUrlsObj.keys()
            while (iter.hasNext()) add(iter.next())
        }
        val mresultKeys = buildList {
            val iter = decodedMresult.keys()
            while (iter.hasNext()) add(iter.next())
        }
        val sharedKeys = siteKeys.intersect(mresultKeys.toSet())

        for (key in sharedKeys) {
            val base = siteUrlsObj.optString(key).trimEnd('/').takeIf { it.isNotBlank() } ?: continue
            val path = decodedMresult.optString(key).trimStart('/').takeIf { it.isNotBlank() } ?: continue
            val fullUrl = "$base/$path"
            val friendlyName = siteFriendlyNamesObj?.optString(key) ?: key

            try {
                when (friendlyName) {
                    "StreamHG", "EarnVids" -> VidHidePro().getUrl(fullUrl, referer, subtitleCallback, callback)
                    "RpmShare", "UpnShare", "StreamP2p" -> VidStack().getUrl(fullUrl, referer, subtitleCallback, callback)
                    else -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("GDMirrorbot", "Failed to extract from $friendlyName at $fullUrl: $e")
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
